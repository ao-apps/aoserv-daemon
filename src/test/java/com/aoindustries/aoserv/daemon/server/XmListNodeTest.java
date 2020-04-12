/*
 * Copyright 2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.server;

import com.aoindustries.io.IoUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.ParseException;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * @author  AO Industries, Inc.
 */
public class XmListNodeTest extends TestCase {

    public XmListNodeTest(String testName) {
        super(testName);
    }

    public static Test suite() {
        TestSuite suite = new TestSuite(XmListNodeTest.class);
        return suite;
    }

    public void testXmListNode() throws IOException, ParseException {
        String parseme;
        Reader in = new InputStreamReader(XmListNodeTest.class.getResourceAsStream("XmListNode-test1.txt"));
        try {
            StringBuilder sb = new StringBuilder();
            IoUtils.copy(in, sb);
            parseme = sb.toString();
        } finally {
            in.close();
        }
        XmListNode.parseResult(parseme);
    }
}
