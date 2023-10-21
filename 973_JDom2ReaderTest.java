/*
 * Copyright (C) 2013, 2015 XStream Committers.
 * All rights reserved.
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 * 
 * Created on 24. June 2012 by Joerg Schaible 
 */
package com.thoughtworks.xstream.io.xml;

import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.JDOMParseException;
import org.jdom2.input.SAXBuilder;

import java.io.StringReader;

public class JDom2ReaderTest extends AbstractXMLReaderTest {

    // factory method
    protected HierarchicalStreamReader createReader(String xml) throws Exception {
        return new JDom2Driver().createReader(new StringReader(xml));
    }

    public void testCanReadFromElementOfLargerDocument() throws Exception {
        String xml ="" +
                "<big>" +
                "  <small>" +
                "    <tiny/>" +
                "  </small>" +
                "  <small-two>" +
                "  </small-two>" +
                "</big>";
        Document document = new SAXBuilder().build(new StringReader(xml));
        Element element = document.getRootElement().getChild("small");

        HierarchicalStreamReader xmlReader = new JDom2Reader(element);
        assertEquals("small", xmlReader.getNodeName());
        xmlReader.moveDown();
        assertEquals("tiny", xmlReader.getNodeName());
    }

    @Override
    public void testIsXXEVulnerable() throws Exception {
        try {
            super.testIsXXEVulnerable();
            fail("Thrown " + JDOMParseException.class.getName() + " expected");
        } catch (final JDOMParseException e) {
            final String message = e.getMessage();
            if (message.contains("Package")) {
                throw e;
            }
        }
    }

    // inherits tests from superclass

}
