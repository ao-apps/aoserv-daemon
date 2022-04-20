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
 * Encapsulates the output of the /bin/netstat -s command.
 *
 * @author  AO Industries, Inc.
 */
public final class NetStatS {

  public final long
    icmp_in_message,
    icmp_in_fail,
    icmp_in_unreachable,
    icmp_in_timeout,
    icmp_in_quench,
    icmp_in_redirect,
    icmp_in_echo_request,
    icmp_in_echo_reply,
    icmp_out_message,
    icmp_out_fail,
    icmp_out_unreachable,
    icmp_out_timeout,
    icmp_out_redirect,
    icmp_out_echo_reply
  ;

  public final long
    ip_packet,
    ip_invalid_headers,
    ip_forward,
    ip_discard,
    ip_deliver,
    ip_request,
    ip_out_drop,
    ip_out_drop_no_route,
    ip_out_drop_timeout,
    ip_ra_req,
    ip_ra_ok,
    ip_ra_fail
  ;

  public final long
    tcp_active_connect,
    tcp_passive_connect,
    tcp_fail_connect,
    tcp_in_reset,
    tcp_connect,
    tcp_segment_receive,
    tcp_segment_send,
    tcp_segment_resend,
    tcp_bad_segment_receive,
    tcp_out_reset
  ;

  public final long
    udp_receive,
    udp_unknown,
    udp_error,
    udp_send
  ;

  public NetStatS() {
    icmp_in_message=icmp_in_fail=icmp_in_unreachable=icmp_in_timeout=icmp_in_quench=icmp_in_redirect=icmp_in_echo_request=icmp_in_echo_reply=icmp_out_message=icmp_out_fail=icmp_out_unreachable=icmp_out_timeout=icmp_out_redirect=icmp_out_echo_reply=0;
    ip_packet=ip_invalid_headers=ip_forward=ip_discard=ip_deliver=ip_request=ip_out_drop=ip_out_drop_no_route=ip_out_drop_timeout=ip_ra_req=ip_ra_ok=ip_ra_fail=0;
    tcp_active_connect=tcp_passive_connect=tcp_fail_connect=tcp_in_reset=tcp_connect=tcp_segment_receive=tcp_segment_send=tcp_segment_resend=tcp_bad_segment_receive=tcp_out_reset=0;
    udp_receive=udp_unknown=udp_error=udp_send=0;
  }
}
