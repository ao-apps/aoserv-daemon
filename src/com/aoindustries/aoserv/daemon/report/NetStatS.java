package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import java.io.*;

/**
 * Encapsulates the output of the /bin/netstat -s command.
 *
 * @author  AO Industries, Inc.
 */
final public class NetStatS {

    final public long
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

    final public long
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

    final public long
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

    final public long
        udp_receive,
        udp_unknown,
        udp_error,
        udp_send
    ;

    public NetStatS() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, NetStatS.class, "<init>()", null);
        try {
            icmp_in_message=icmp_in_fail=icmp_in_unreachable=icmp_in_timeout=icmp_in_quench=icmp_in_redirect=icmp_in_echo_request=icmp_in_echo_reply=icmp_out_message=icmp_out_fail=icmp_out_unreachable=icmp_out_timeout=icmp_out_redirect=icmp_out_echo_reply=0;
            ip_packet=ip_invalid_headers=ip_forward=ip_discard=ip_deliver=ip_request=ip_out_drop=ip_out_drop_no_route=ip_out_drop_timeout=ip_ra_req=ip_ra_ok=ip_ra_fail=0;
            tcp_active_connect=tcp_passive_connect=tcp_fail_connect=tcp_in_reset=tcp_connect=tcp_segment_receive=tcp_segment_send=tcp_segment_resend=tcp_bad_segment_receive=tcp_out_reset=0;
            udp_receive=udp_unknown=udp_error=udp_send=0;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
}