/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.report;

/**
 * Encapsulates the output of the <code>/bin/netstat -s</code> command.
 *
 * @author  AO Industries, Inc.
 */
public final class NetStatS {

  public final long icmpInMessage;
  public final long icmpInFail;
  public final long icmpInUnreachable;
  public final long icmpInTimeout;
  public final long icmpInQuench;
  public final long icmpInRedirect;
  public final long icmpInEchoRequest;
  public final long icmpInEchoReply;
  public final long icmpOutMessage;
  public final long icmpOutFail;
  public final long icmpOutUnreachable;
  public final long icmpOutTimeout;
  public final long icmpOutRedirect;
  public final long icmpOutEchoReply;

  public final long ipPacket;
  public final long ipInvalidHeaders;
  public final long ipForward;
  public final long ipDiscard;
  public final long ipDeliver;
  public final long ipRequest;
  public final long ipOutDrop;
  public final long ipOutDropNoRoute;
  public final long ipOutDropTimeout;
  public final long ipRaReq;
  public final long ipRaOk;
  public final long ipRaFail;

  public final long tcpActiveConnect;
  public final long tcpPassiveConnect;
  public final long tcpFailConnect;
  public final long tcpInReset;
  public final long tcpConnect;
  public final long tcpSegmentReceive;
  public final long tcpSegmentSend;
  public final long tcpSegmentResend;
  public final long tcpBadSegmentReceive;
  public final long tcpOutReset;

  public final long udpReceive;
  public final long udpUnknown;
  public final long udpError;
  public final long udpSend;

  public NetStatS() {
    icmpInMessage = icmpInFail = icmpInUnreachable = icmpInTimeout = icmpInQuench = icmpInRedirect = icmpInEchoRequest =
        icmpInEchoReply = icmpOutMessage = icmpOutFail = icmpOutUnreachable = icmpOutTimeout = icmpOutRedirect =
        icmpOutEchoReply = 0;
    ipPacket = ipInvalidHeaders = ipForward = ipDiscard = ipDeliver = ipRequest = ipOutDrop = ipOutDropNoRoute =
        ipOutDropTimeout = ipRaReq = ipRaOk = ipRaFail = 0;
    tcpActiveConnect = tcpPassiveConnect = tcpFailConnect = tcpInReset = tcpConnect = tcpSegmentReceive =
        tcpSegmentSend = tcpSegmentResend = tcpBadSegmentReceive = tcpOutReset = 0;
    udpReceive = udpUnknown = udpError = udpSend = 0;
  }
}
