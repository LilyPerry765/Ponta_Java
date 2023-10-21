/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.socket.sockjs.support;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.sockjs.SockJsException;
import org.springframework.web.socket.sockjs.SockJsService;
import org.springframework.web.util.WebUtils;

/**
 * An abstract base class for {@link SockJsService} implementations that provides SockJS
 * path resolution and handling of static SockJS requests (e.g. "/info", "/iframe.html",
 * etc). Sub-classes must handle session URLs (i.e. transport-specific requests).
 *
 * By default, only same origin requests are allowed. Use {@link #setAllowedOrigins(List)}
 * to specify a list of allowed origins (a list containing "*" will allow all origins).
 *
 * @author Rossen Stoyanchev
 * @author Sebastien Deleuze
 * @since 4.0
 */
public abstract class AbstractSockJsService implements SockJsService {

	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

	private static final long ONE_YEAR = TimeUnit.DAYS.toSeconds(365);

	private static final Random random = new Random();

	private static final String XFRAME_OPTIONS_HEADER = "X-Frame-Options";


	protected final Log logger = LogFactory.getLog(getClass());

	private final TaskScheduler taskScheduler;

	private String name = "SockJSService@" + ObjectUtils.getIdentityHexString(this);

	private String clientLibraryUrl = "https://cdn.jsdelivr.net/sockjs/0.3.4/sockjs.min.js";

	private int streamBytesLimit = 128 * 1024;

	private boolean sessionCookieNeeded = true;

	private long heartbeatTime = 25 * 1000;

	private long disconnectDelay = 5 * 1000;

	private int httpMessageCacheSize = 100;

	private boolean webSocketEnabled = true;

	private final List<String> allowedOrigins = new ArrayList<String>();

	private boolean suppressCors = false;


	public AbstractSockJsService(TaskScheduler scheduler) {
		Assert.notNull(scheduler, "TaskScheduler must not be null");
		this.taskScheduler = scheduler;
	}


	/**
	 * A scheduler instance to use for scheduling heart-beat messages.
	 */
	public TaskScheduler getTaskScheduler() {
		return this.taskScheduler;
	}

	/**
	 * Set a unique name for this service (mainly for logging purposes).
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Return the unique name associated with this service.
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Transports with no native cross-domain communication (e.g. "eventsource",
	 * "htmlfile") must get a simple page from the "foreign" domain in an invisible
	 * iframe so that code in the iframe can run from  a domain local to the SockJS
	 * server. Since the iframe needs to load the SockJS javascript client library,
	 * this property allows specifying where to load it from.
	 * <p>By default this is set to point to
	 * "https://cdn.jsdelivr.net/sockjs/0.3.4/sockjs.min.js".
	 * However, it can also be set to point to a URL served by the application.
	 * <p>Note that it's possible to specify a relative URL in which case the URL
	 * must be relative to the iframe URL. For example assuming a SockJS endpoint
	 * mapped to "/sockjs", and resulting iframe URL "/sockjs/iframe.html", then the
	 * the relative URL must start with "../../" to traverse up to the location
	 * above the SockJS mapping. In case of a prefix-based Servlet mapping one more
	 * traversal may be needed.
	 */
	public void setSockJsClientLibraryUrl(String clientLibraryUrl) {
		this.clientLibraryUrl = clientLibraryUrl;
	}

	/**
	 * Return he URL to the SockJS JavaScript client library.
	 */
	public String getSockJsClientLibraryUrl() {
		return this.clientLibraryUrl;
	}

	/**
	 * Streaming transports save responses on the client side and don't free
	 * memory used by delivered messages. Such transports need to recycle the
	 * connection once in a while. This property sets a minimum number of bytes
	 * that can be sent over a single HTTP streaming request before it will be
	 * closed. After that client will open a new request. Setting this value to
	 * one effectively disables streaming and will make streaming transports to
	 * behave like polling transports.
	 * <p>The default value is 128K (i.e. 128 * 1024).
	 */
	public void setStreamBytesLimit(int streamBytesLimit) {
		this.streamBytesLimit = streamBytesLimit;
	}

	/**
	 * Return the minimum number of bytes that can be sent over a single HTTP
	 * streaming request before it will be closed.
	 */
	public int getStreamBytesLimit() {
		return this.streamBytesLimit;
	}

	/**
	 * The SockJS protocol requires a server to respond to an initial "/info" request from
	 * clients with a "cookie_needed" boolean property that indicates whether the use of a
	 * JSESSIONID cookie is required for the application to function correctly, e.g. for
	 * load balancing or in Java Servlet containers for the use of an HTTP session.
	 * <p>This is especially important for IE 8,9 that support XDomainRequest -- a modified
	 * AJAX/XHR -- that can do requests across domains but does not send any cookies. In
	 * those cases, the SockJS client prefers the "iframe-htmlfile" transport over
	 * "xdr-streaming" in order to be able to send cookies.
	 * <p>The SockJS protocol also expects a SockJS service to echo back the JSESSIONID
	 * cookie when this property is set to true. However, when running in a Servlet
	 * container this is not necessary since the container takes care of it.
	 * <p>The default value is "true" to maximize the chance for applications to work
	 * correctly in IE 8,9 with support for cookies (and the JSESSIONID cookie in
	 * particular). However, an application can choose to set this to "false" if
	 * the use of cookies (and HTTP session) is not required.
	 */
	public void setSessionCookieNeeded(boolean sessionCookieNeeded) {
		this.sessionCookieNeeded = sessionCookieNeeded;
	}

	/**
	 * Return whether the JSESSIONID cookie is required for the application to function.
	 */
	public boolean isSessionCookieNeeded() {
		return this.sessionCookieNeeded;
	}

	/**
	 * Specify the amount of time in milliseconds when the server has not sent
	 * any messages and after which the server should send a heartbeat frame
	 * to the client in order to keep the connection from breaking.
	 * <p>The default value is 25,000 (25 seconds).
	 */
	public void setHeartbeatTime(long heartbeatTime) {
		this.heartbeatTime = heartbeatTime;
	}

	/**
	 * Return the amount of time in milliseconds when the server has not sent
	 * any messages.
	 */
	public long getHeartbeatTime() {
		return this.heartbeatTime;
	}

	/**
	 * The amount of time in milliseconds before a client is considered
	 * disconnected after not having a receiving connection, i.e. an active
	 * connection over which the server can send data to the client.
	 * <p>The default value is 5000.
	 */
	public void setDisconnectDelay(long disconnectDelay) {
		this.disconnectDelay = disconnectDelay;
	}

	/**
	 * Return the amount of time in milliseconds before a client is considered disconnected.
	 */
	public long getDisconnectDelay() {
		return this.disconnectDelay;
	}

	/**
	 * The number of server-to-client messages that a session can cache while waiting
	 * for the next HTTP polling request from the client. All HTTP transports use this
	 * property since even streaming transports recycle HTTP requests periodically.
	 * <p>The amount of time between HTTP requests should be relatively brief and will
	 * not exceed the allows disconnect delay (see {@link #setDisconnectDelay(long)});
	 * 5 seconds by default.
	 * <p>The default size is 100.
	 */
	public void setHttpMessageCacheSize(int httpMessageCacheSize) {
		this.httpMessageCacheSize = httpMessageCacheSize;
	}

	/**
	 * Return the size of the HTTP message cache.
	 */
	public int getHttpMessageCacheSize() {
		return this.httpMessageCacheSize;
	}

	/**
	 * Some load balancers do not support WebSocket. This option can be used to
	 * disable the WebSocket transport on the server side.
	 * <p>The default value is "true".
	 */
	public void setWebSocketEnabled(boolean webSocketEnabled) {
		this.webSocketEnabled = webSocketEnabled;
	}

	/**
	 * Return whether WebSocket transport is enabled.
	 */
	public boolean isWebSocketEnabled() {
		return this.webSocketEnabled;
	}

	/**
	 * Configure allowed {@code Origin} header values. This check is mostly
	 * designed for browsers. There is nothing preventing other types of client
	 * to modify the {@code Origin} header value.
	 * <p>When SockJS is enabled and origins are restricted, transport types
	 * that do not allow to check request origin (JSONP and Iframe based
	 * transports) are disabled. As a consequence, IE 6 to 9 are not supported
	 * when origins are restricted.
	 * <p>Each provided allowed origin must have a scheme, and optionally a port
	 * (e.g. "http://example.org", "http://example.org:9090"). An allowed origin
	 * string may also be "*" in which case all origins are allowed.
	 * @since 4.1.2
	 * @see <a href="https://tools.ietf.org/html/rfc6454">RFC 6454: The Web Origin Concept</a>
	 * @see <a href="https://github.com/sockjs/sockjs-client#supported-transports-by-browser-html-served-from-http-or-https">SockJS supported transports by browser</a>
	 */
	public void setAllowedOrigins(List<String> allowedOrigins) {
		Assert.notNull(allowedOrigins, "Allowed origin List must not be null");
		this.allowedOrigins.clear();
		this.allowedOrigins.addAll(allowedOrigins);
	}

	/**
	 * @since 4.1.2
	 * @see #setAllowedOrigins(List)
	 */
	public List<String> getAllowedOrigins() {
		return Collections.unmodifiableList(this.allowedOrigins);
	}

	/**
	 * This option can be used to disable automatic addition of CORS headers for
	 * SockJS requests.
	 * <p>The default value is "false".
	 * @since 4.1.2
	 */
	public void setSuppressCors(boolean suppressCors) {
		this.suppressCors = suppressCors;
	}

	/**
	 * @since 4.1.2
	 * @see #setSuppressCors(boolean)
	 */
	public boolean shouldSuppressCors() {
		return this.suppressCors;
	}


	/**
	 * This method determines the SockJS path and handles SockJS static URLs.
	 * Session URLs and raw WebSocket requests are delegated to abstract methods.
	 */
	@Override
	public final void handleRequest(ServerHttpRequest request, ServerHttpResponse response,
			String sockJsPath, WebSocketHandler wsHandler) throws SockJsException {

		if (sockJsPath == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("Expected SockJS path. Failing request: " + request.getURI());
			}
			response.setStatusCode(HttpStatus.NOT_FOUND);
			return;
		}

		try {
			request.getHeaders();
		}
		catch (InvalidMediaTypeException ex) {
			// As per SockJS protocol content-type can be ignored (it's always json)
		}

		String requestInfo = (logger.isDebugEnabled() ? request.getMethod() + " " + request.getURI() : null);

		try {
			if (sockJsPath.equals("") || sockJsPath.equals("/")) {
				if (requestInfo != null) {
					logger.debug("Processing transport request: " + requestInfo);
				}
				response.getHeaders().setContentType(new MediaType("text", "plain", UTF8_CHARSET));
				response.getBody().write("Welcome to SockJS!\n".getBytes(UTF8_CHARSET));
			}

			else if (sockJsPath.equals("/info")) {
				if (requestInfo != null) {
					logger.debug("Processing transport request: " + requestInfo);
				}
				this.infoHandler.handle(request, response);
			}

			else if (sockJsPath.matches("/iframe[0-9-.a-z_]*.html")) {
				if (!this.allowedOrigins.isEmpty() && !this.allowedOrigins.contains("*")) {
					if (requestInfo != null) {
						logger.debug("Iframe support is disabled when an origin check is required. " +
								"Ignoring transport request: " + requestInfo);
					}
					response.setStatusCode(HttpStatus.NOT_FOUND);
					return;
				}
				if (this.allowedOrigins.isEmpty()) {
					response.getHeaders().add(XFRAME_OPTIONS_HEADER, "SAMEORIGIN");
				}
				if (requestInfo != null) {
					logger.debug("Processing transport request: " + requestInfo);
				}
				this.iframeHandler.handle(request, response);
			}

			else if (sockJsPath.equals("/websocket")) {
				if (isWebSocketEnabled()) {
					if (requestInfo != null) {
						logger.debug("Processing transport request: " + requestInfo);
					}
					handleRawWebSocketRequest(request, response, wsHandler);
				}
				else if (requestInfo != null) {
					logger.debug("WebSocket disabled. Ignoring transport request: " + requestInfo);
				}
			}

			else {
				String[] pathSegments = StringUtils.tokenizeToStringArray(sockJsPath.substring(1), "/");
				if (pathSegments.length != 3) {
					if (logger.isWarnEnabled()) {
						logger.warn("Invalid SockJS path '" + sockJsPath + "' - required to have 3 path segments");
					}
					if (requestInfo != null) {
						logger.debug("Ignoring transport request: " + requestInfo);
					}
					response.setStatusCode(HttpStatus.NOT_FOUND);
					return;
				}

				String serverId = pathSegments[0];
				String sessionId = pathSegments[1];
				String transport = pathSegments[2];

				if (!isWebSocketEnabled() && transport.equals("websocket")) {
					if (requestInfo != null) {
						logger.debug("WebSocket disabled. Ignoring transport request: " + requestInfo);
					}
					response.setStatusCode(HttpStatus.NOT_FOUND);
					return;
				}
				else if (!validateRequest(serverId, sessionId, transport)) {
					if (requestInfo != null) {
						logger.debug("Ignoring transport request: " + requestInfo);
					}
					response.setStatusCode(HttpStatus.NOT_FOUND);
					return;
				}

				if (requestInfo != null) {
					logger.debug("Processing transport request: " + requestInfo);
				}
				handleTransportRequest(request, response, wsHandler, sessionId, transport);
			}
			response.close();
		}
		catch (IOException ex) {
			throw new SockJsException("Failed to write to the response", null, ex);
		}
	}

	protected boolean validateRequest(String serverId, String sessionId, String transport) {
		if (!StringUtils.hasText(serverId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(transport)) {
			logger.warn("No server, session, or transport path segment in SockJS request.");
			return false;
		}

		// Server and session id's must not contain "."
		if (serverId.contains(".") || sessionId.contains(".")) {
			logger.warn("Either server or session contains a \".\" which is not allowed by SockJS protocol.");
			return false;
		}

		return true;
	}


	/**
	 * Handle request for raw WebSocket communication, i.e. without any SockJS message framing.
	 */
	protected abstract void handleRawWebSocketRequest(ServerHttpRequest request,
			ServerHttpResponse response, WebSocketHandler webSocketHandler) throws IOException;

	/**
	 * Handle a SockJS session URL (i.e. transport-specific request).
	 */
	protected abstract void handleTransportRequest(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler webSocketHandler, String sessionId, String transport) throws SockJsException;

	/**
	 * Check the {@code Origin} header value and eventually call {@link #addCorsHeaders(ServerHttpRequest, ServerHttpResponse, HttpMethod...)}.
	 * If the request origin is not allowed, the request is rejected.
	 * @return false if the request is rejected, else true
	 * @since 4.1.2
	 */
	protected boolean checkAndAddCorsHeaders(ServerHttpRequest request, ServerHttpResponse response, HttpMethod... httpMethods) {
		HttpHeaders requestHeaders = request.getHeaders();
		HttpHeaders responseHeaders = response.getHeaders();
		String origin = requestHeaders.getOrigin();

		if (origin == null) {
			return true;
		}

		if (!WebUtils.isValidOrigin(request, this.allowedOrigins)) {
			if (logger.isWarnEnabled()) {
				logger.warn("Origin header value '" + origin + "' not allowed.");
			}
			response.setStatusCode(HttpStatus.FORBIDDEN);
			return false;
		}

		boolean hasCorsResponseHeaders = false;
		try {
			// Perhaps a CORS Filter has already added this?
			hasCorsResponseHeaders = !CollectionUtils.isEmpty(responseHeaders.get("Access-Control-Allow-Origin"));
		}
		catch (NullPointerException npe) {
			// See SPR-11919 and https://issues.jboss.org/browse/WFLY-3474
		}

		if (!this.suppressCors && !hasCorsResponseHeaders) {
			addCorsHeaders(request, response, httpMethods);
		}
		return true;
	}

	protected void addCorsHeaders(ServerHttpRequest request, ServerHttpResponse response, HttpMethod... httpMethods) {
		HttpHeaders requestHeaders = request.getHeaders();
		HttpHeaders responseHeaders = response.getHeaders();

		responseHeaders.add("Access-Control-Allow-Origin", requestHeaders.getFirst("Origin"));
		responseHeaders.add("Access-Control-Allow-Credentials", "true");

		List<String> accessControllerHeaders = requestHeaders.get("Access-Control-Request-Headers");
		if (accessControllerHeaders != null) {
			for (String header : accessControllerHeaders) {
				responseHeaders.add("Access-Control-Allow-Headers", header);
			}
		}

		if (!ObjectUtils.isEmpty(httpMethods)) {
			responseHeaders.add("Access-Control-Allow-Methods", StringUtils.arrayToDelimitedString(httpMethods, ", "));
			responseHeaders.add("Access-Control-Max-Age", String.valueOf(ONE_YEAR));
		}
		responseHeaders.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
	}

	protected void addCacheHeaders(ServerHttpResponse response) {
		response.getHeaders().setCacheControl("public, max-age=" + ONE_YEAR);
		response.getHeaders().setExpires(new Date().getTime() + ONE_YEAR * 1000);
		response.getHeaders().add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
	}

	protected void addNoCacheHeaders(ServerHttpResponse response) {
		response.getHeaders().setCacheControl("no-store, no-cache, must-revalidate, max-age=0");
	}

	protected void sendMethodNotAllowed(ServerHttpResponse response, HttpMethod... httpMethods) {
		logger.warn("Sending Method Not Allowed (405)");
		response.setStatusCode(HttpStatus.METHOD_NOT_ALLOWED);
		response.getHeaders().setAllow(new HashSet<HttpMethod>(Arrays.asList(httpMethods)));
	}


	private interface SockJsRequestHandler {

		void handle(ServerHttpRequest request, ServerHttpResponse response) throws IOException;
	}


	private final SockJsRequestHandler infoHandler = new SockJsRequestHandler() {

		private static final String INFO_CONTENT =
				"{\"entropy\":%s,\"origins\":[\"*:*\"],\"cookie_needed\":%s,\"websocket\":%s}";

		@Override
		public void handle(ServerHttpRequest request, ServerHttpResponse response) throws IOException {
			if (HttpMethod.GET.equals(request.getMethod())) {
				addNoCacheHeaders(response);
				if (checkAndAddCorsHeaders(request, response)) {
					response.getHeaders().setContentType(new MediaType("application", "json", UTF8_CHARSET));
					String content = String.format(INFO_CONTENT, random.nextInt(), isSessionCookieNeeded(), isWebSocketEnabled());
					response.getBody().write(content.getBytes());
				}
			}
			else if (HttpMethod.OPTIONS.equals(request.getMethod())) {
				if (checkAndAddCorsHeaders(request, response, HttpMethod.OPTIONS, HttpMethod.GET)) {
					addCacheHeaders(response);
					response.setStatusCode(HttpStatus.NO_CONTENT);
				}
			}
			else {
				sendMethodNotAllowed(response, HttpMethod.OPTIONS, HttpMethod.GET);
			}
		}
	};


	private final SockJsRequestHandler iframeHandler = new SockJsRequestHandler() {

		private static final String IFRAME_CONTENT =
				"<!DOCTYPE html>\n" +
		        "<html>\n" +
		        "<head>\n" +
		        "  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />\n" +
		        "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" +
		        "  <script>\n" +
		        "    document.domain = document.domain;\n" +
		        "    _sockjs_onload = function(){SockJS.bootstrap_iframe();};\n" +
		        "  </script>\n" +
		        "  <script src=\"%s\"></script>\n" +
		        "</head>\n" +
		        "<body>\n" +
		        "  <h2>Don't panic!</h2>\n" +
		        "  <p>This is a SockJS hidden iframe. It's used for cross domain magic.</p>\n" +
		        "</body>\n" +
		        "</html>";

		@Override
		public void handle(ServerHttpRequest request, ServerHttpResponse response) throws IOException {
			if (!HttpMethod.GET.equals(request.getMethod())) {
				sendMethodNotAllowed(response, HttpMethod.GET);
				return;
			}

			String content = String.format(IFRAME_CONTENT, getSockJsClientLibraryUrl());
			byte[] contentBytes = content.getBytes(UTF8_CHARSET);
			StringBuilder builder = new StringBuilder("\"0");
			DigestUtils.appendMd5DigestAsHex(contentBytes, builder);
			builder.append('"');
			String etagValue = builder.toString();

			List<String> ifNoneMatch = request.getHeaders().getIfNoneMatch();
			if (!CollectionUtils.isEmpty(ifNoneMatch) && ifNoneMatch.get(0).equals(etagValue)) {
				response.setStatusCode(HttpStatus.NOT_MODIFIED);
				return;
			}

			response.getHeaders().setContentType(new MediaType("text", "html", UTF8_CHARSET));
			response.getHeaders().setContentLength(contentBytes.length);

			// No cache in order to check every time if IFrame are authorized
			addNoCacheHeaders(response);
			response.getHeaders().setETag(etagValue);
			response.getBody().write(contentBytes);
		}
	};

}
