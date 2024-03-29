/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2012, 2013, 2018, 2020, 2021, 2022  AO Industries, Inc.
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

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
public final class XmListNode {

  /**
   * Parses the result of xm list -l into a tree of objects that have a name
   * and then a list of values.  Each value may either by a String or another list.
   * The root node will have a name of <code>""</code>.
   */
  static XmListNode parseResult(String result) throws ParseException {
    XmListNode rootNode = new XmListNode("");
    int pos = parseResult(rootNode, result, 0);
    // Everything after pos should be whitespace
    if (result.substring(pos).trim().length() > 0) {
      throw new ParseException("Non-whitespace remaining after completed parsing in:\n" + result, pos);
    }
    return rootNode;
  }

  /**
   * Parses into the current map.
   */
  static int parseResult(XmListNode node, String result, int pos) throws ParseException {
    int len = result.length();

    while (pos < len) {
      // Look for the next non-whitespace character
      while (pos < len && result.charAt(pos) <= ' ') {
        pos++;
      }
      if (pos < len) {
        if (result.charAt(pos) == '(') {
          // If is a (, start a sublist
          int nameStart = ++pos;
          while (pos < len && result.charAt(pos) > ' ' && result.charAt(pos) != ')') {
            pos++;
          }
          if (pos >= len) {
            throw new ParseException("Unexpected end of result in:\n" + result, pos);
          }
          String name = result.substring(nameStart, pos);
          if (name.length() > 0) {
            XmListNode newNode = new XmListNode(name);
            pos = parseResult(newNode, result, pos);
            if (pos >= len) {
              throw new ParseException("Unexpected end of result in:\n" + result, pos);
            }
            node.list.add(newNode);
          }
          // Character at pos should be )
          if (result.charAt(pos) != ')') {
            throw new ParseException("')' expected in:\n" + result, pos);
          }
          pos++; // Skip past )
        } else {
          // Is a raw value, parse up to either whitespace or )
          int valueStart = pos;
          while (pos < len && (result.charAt(pos) > ' ' && result.charAt(pos) != ')')) {
            pos++;
          }
          if (pos >= len) {
            throw new ParseException("Unexpected end of result in:\n" + result, pos);
          }
          String value = result.substring(valueStart, pos).trim();
          if (value.length() > 0) {
            node.list.add(value);
          }
          if (result.charAt(pos) == ')') {
            // Found ), end list
            return pos;
          }
        }
      }
    }
    return pos;
  }

  private final String id;
  private final List<Object> list;

  private XmListNode(String id) {
    this.id = id;
    this.list = new ArrayList<>();
  }

  public String getId() {
    return id;
  }

  public long size() {
    return list.size();
  }

  public Object get(int index) {
    return list.get(index);
  }

  public XmListNode getChild(String childNodeName) throws ParseException {
    for (Object child : list) {
      if (child instanceof XmListNode) {
        XmListNode childNode = (XmListNode) child;
        if (childNode.id.equals(childNodeName)) {
          return childNode;
        }
      }
    }
    throw new ParseException("No child node named '" + childNodeName + "' found", 0);
  }

  public String getString(String childNodeName) throws ParseException {
    XmListNode childNode = getChild(childNodeName);
    // Should have a sublist of length 1
    if (childNode.list.size() != 1) {
      throw new ParseException("child list must have length 1, got " + childNode.list.size(), 0);
    }
    Object childNodeValue = childNode.list.get(0);
    if (!(childNodeValue instanceof String)) {
      throw new ParseException("child node list element is not a String", 0);
    }
    return (String) childNodeValue;
  }

  public int getInt(String childNodeName) throws ParseException {
    return Integer.parseInt(getString(childNodeName));
  }

  public long getLong(String childNodeName) throws ParseException {
    return Long.parseLong(getString(childNodeName));
  }

  public float getFloat(String childNodeName) throws ParseException {
    return Float.parseFloat(getString(childNodeName));
  }

  public double getDouble(String childNodeName) throws ParseException {
    return Double.parseDouble(getString(childNodeName));
  }
}
