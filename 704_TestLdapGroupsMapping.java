/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

import javax.naming.CommunicationException;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.apache.hadoop.security.alias.JavaKeyStoreProvider;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class TestLdapGroupsMapping extends TestLdapGroupsMappingBase {
  @Before
  public void setupMocks() throws NamingException {
    SearchResult mockUserResult = mock(SearchResult.class);
    when(mockUserNamingEnum.nextElement()).thenReturn(mockUserResult);
    when(mockUserResult.getNameInNamespace()).thenReturn("CN=some_user,DC=test,DC=com");
  }
  
  @Test
  public void testGetGroups() throws IOException, NamingException {
    // The search functionality of the mock context is reused, so we will
    // return the user NamingEnumeration first, and then the group
    when(mockContext.search(anyString(), anyString(), any(Object[].class),
        any(SearchControls.class)))
        .thenReturn(mockUserNamingEnum, mockGroupNamingEnum);
    
    doTestGetGroups(Arrays.asList(testGroups), 2);
  }

  @Test
  public void testGetGroupsWithConnectionClosed() throws IOException, NamingException {
    // The case mocks connection is closed/gc-ed, so the first search call throws CommunicationException,
    // then after reconnected return the user NamingEnumeration first, and then the group
    when(mockContext.search(anyString(), anyString(), any(Object[].class),
        any(SearchControls.class)))
        .thenThrow(new CommunicationException("Connection is closed"))
        .thenReturn(mockUserNamingEnum, mockGroupNamingEnum);
    
    // Although connection is down but after reconnected it still should retrieve the result groups
    doTestGetGroups(Arrays.asList(testGroups), 1 + 2); // 1 is the first failure call 
  }

  @Test
  public void testGetGroupsWithLdapDown() throws IOException, NamingException {
    // This mocks the case where Ldap server is down, and always throws CommunicationException 
    when(mockContext.search(anyString(), anyString(), any(Object[].class),
        any(SearchControls.class)))
        .thenThrow(new CommunicationException("Connection is closed"));
    
    // Ldap server is down, no groups should be retrieved
    doTestGetGroups(Arrays.asList(new String[] {}), 
        LdapGroupsMapping.RECONNECT_RETRY_COUNT);
  }
  
  private void doTestGetGroups(List<String> expectedGroups, int searchTimes) throws IOException, NamingException {  
    Configuration conf = new Configuration();
    // Set this, so we don't throw an exception
    conf.set(LdapGroupsMapping.LDAP_URL_KEY, "ldap://test");
    
    mappingSpy.setConf(conf);
    // Username is arbitrary, since the spy is mocked to respond the same,
    // regardless of input
    List<String> groups = mappingSpy.getGroups("some_user");
    
    Assert.assertEquals(expectedGroups, groups);
    
    // We should have searched for a user, and then two groups
    verify(mockContext, times(searchTimes)).search(anyString(),
                                         anyString(),
                                         any(Object[].class),
                                         any(SearchControls.class));
  }
  
  @Test
  public void testExtractPassword() throws IOException {
    File testDir = new File(System.getProperty("test.build.data", 
                                               "target/test-dir"));
    testDir.mkdirs();
    File secretFile = new File(testDir, "secret.txt");
    Writer writer = new FileWriter(secretFile);
    writer.write("hadoop");
    writer.close();
    
    LdapGroupsMapping mapping = new LdapGroupsMapping();
    Assert.assertEquals("hadoop",
        mapping.extractPassword(secretFile.getPath()));
  }

  @Test
  public void testConfGetPassword() throws Exception {
    File testDir = new File(System.getProperty("test.build.data",
                                               "target/test-dir"));
    Configuration conf = new Configuration();
    final Path jksPath = new Path(testDir.toString(), "test.jks");
    final String ourUrl =
        JavaKeyStoreProvider.SCHEME_NAME + "://file" + jksPath.toUri();

    File file = new File(testDir, "test.jks");
    file.delete();
    conf.set(CredentialProviderFactory.CREDENTIAL_PROVIDER_PATH, ourUrl);

    CredentialProvider provider =
        CredentialProviderFactory.getProviders(conf).get(0);
    char[] bindpass = {'b', 'i', 'n', 'd', 'p', 'a', 's', 's'};
    char[] storepass = {'s', 't', 'o', 'r', 'e', 'p', 'a', 's', 's'};

    // ensure that we get nulls when the key isn't there
    assertEquals(null, provider.getCredentialEntry(
        LdapGroupsMapping.BIND_PASSWORD_KEY));
    assertEquals(null, provider.getCredentialEntry
        (LdapGroupsMapping.LDAP_KEYSTORE_PASSWORD_KEY));

    // create new aliases
    try {
      provider.createCredentialEntry(
          LdapGroupsMapping.BIND_PASSWORD_KEY, bindpass);

      provider.createCredentialEntry(
          LdapGroupsMapping.LDAP_KEYSTORE_PASSWORD_KEY, storepass);
      provider.flush();
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    // make sure we get back the right key
    assertArrayEquals(bindpass, provider.getCredentialEntry(
        LdapGroupsMapping.BIND_PASSWORD_KEY).getCredential());
    assertArrayEquals(storepass, provider.getCredentialEntry(
        LdapGroupsMapping.LDAP_KEYSTORE_PASSWORD_KEY).getCredential());

    LdapGroupsMapping mapping = new LdapGroupsMapping();
    Assert.assertEquals("bindpass",
        mapping.getPassword(conf, LdapGroupsMapping.BIND_PASSWORD_KEY, ""));
    Assert.assertEquals("storepass",
        mapping.getPassword(conf, LdapGroupsMapping.LDAP_KEYSTORE_PASSWORD_KEY,
           ""));
    // let's make sure that a password that doesn't exist returns an
    // empty string as currently expected and used to trigger a call to
    // extract password
    Assert.assertEquals("", mapping.getPassword(conf,"invalid-alias", ""));
  }
}
