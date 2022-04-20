/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2013, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.server;

import com.aoapps.lang.io.IoUtils;
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
    try (Reader in = new InputStreamReader(XmListNodeTest.class.getResourceAsStream("XmListNode-test1.txt"))) {
      StringBuilder sb = new StringBuilder();
      IoUtils.copy(in, sb);
      parseme = sb.toString();
    }
    XmListNode.parseResult(parseme);
  }
}
