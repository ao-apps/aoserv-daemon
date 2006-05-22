package com.aoindustries.aoserv.daemon.monitor;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.profiler.Profiler;

/**
 * @author  AO Industries, Inc.
 */
final public class NetStat {

    public String net_protocol;
    public String ip_address;
    public int port;

    public NetStat(String net_protocol, String ip_address, int port) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, NetStat.class, "<init>(String,String,int)", null);
        try {
            this.net_protocol=net_protocol;
            this.ip_address=ip_address;
            this.port=port;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public boolean equals(Object O) {
        Profiler.startProfile(Profiler.FAST, NetStat.class, "equals(Object)", null);
        try {
            if(O instanceof NetStat) {
                NetStat ns=(NetStat)O;
                return
                    net_protocol.equals(ns.net_protocol)
                    && ip_address.equals(ns.ip_address)
                    && port==ns.port
                ;
            } else return false;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public int hashCode() {
        Profiler.startProfile(Profiler.FAST, NetStat.class, "hashCode()", null);
        try {
            return ip_address.hashCode()+port;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static NetStat parseNetStat(String line) {
        Profiler.startProfile(Profiler.UNKNOWN, NetStat.class, "parseNetStat(String)", null);
        try {
            int len=line.length();

            // find the first word
            int pos=line.indexOf(' ');
            if(pos==-1) return null;
            String protocol=line.substring(0,pos);
            if(
                protocol.equals("Active")
                || protocol.equals("Proto")
                || protocol.equals("unix")
            ) return null;

            /*
             * Find the fourth word, which is the IP address/port combination
             */
            // skip past first blank
            while((++pos)<len && line.charAt(pos)==' ');
            // skip past second word
            pos=line.indexOf(' ', pos);
            if(pos==-1) return null;
            // skip past second blank
            while((++pos)<len && line.charAt(pos)==' ');
            // skip past third word
            pos=line.indexOf(' ', pos);
            if(pos==-1) return null;
            // skip past th-ee-ald blank
            while((++pos)<len && line.charAt(pos)==' ');
            // Find the end of the IP:port column
            int pos2=line.indexOf(' ', pos+1);
            if(pos2==-1) pos2=len;
            // Find the last : in the IP:port area
            int colonPos=line.lastIndexOf(':', pos2);
            if(colonPos==-1) return null;
            String ip=convertIP(line.substring(pos, colonPos));
            int port=Integer.parseInt(line.substring(colonPos+1, pos2));
            return new NetStat(protocol, ip, port);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static String convertIP(String ip) {
        Profiler.startProfile(Profiler.FAST, NetStat.class, "convertIP()", null);
        try {
            if("::ffff:127.0.0.1".equals(ip)) return IPAddress.LOOPBACK_IP;
            if("::".equals(ip)) return IPAddress.WILDCARD_IP;
            if("::1".equals(ip)) return IPAddress.LOOPBACK_IP;
            return ip;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}