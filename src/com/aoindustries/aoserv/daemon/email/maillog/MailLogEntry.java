package com.aoindustries.aoserv.daemon.email.maillog;

/*
 * Copyright 2002-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import java.util.*;

/**
 * Represents one line of the maillog file.
 *
 * @author  AO Industries, Inc.
 */
abstract public class MailLogEntry {

    public static MailLogEntry parseLine(String line) throws IllegalArgumentException {
        Profiler.startProfile(Profiler.FAST, MailLogEntry.class, "parseLine(String)", null);
        try {
            if(line==null) throw new IllegalArgumentException("line is null");
        
            /*
             * Parse the date
             */
            if(line.length()<=15) throw new IllegalArgumentException("line too short to contain date");

            // month
            String mmm=line.substring(0,3);
            int month;
            if(mmm.equalsIgnoreCase("Jan")) month=Calendar.JANUARY;
            else if(mmm.equalsIgnoreCase("Feb")) month=Calendar.FEBRUARY;
            else if(mmm.equalsIgnoreCase("Mar")) month=Calendar.MARCH;
            else if(mmm.equalsIgnoreCase("Apr")) month=Calendar.APRIL;
            else if(mmm.equalsIgnoreCase("May")) month=Calendar.MAY;
            else if(mmm.equalsIgnoreCase("Jun")) month=Calendar.JUNE;
            else if(mmm.equalsIgnoreCase("Jul")) month=Calendar.JULY;
            else if(mmm.equalsIgnoreCase("Aug")) month=Calendar.AUGUST;
            else if(mmm.equalsIgnoreCase("Sep")) month=Calendar.SEPTEMBER;
            else if(mmm.equalsIgnoreCase("Oct")) month=Calendar.OCTOBER;
            else if(mmm.equalsIgnoreCase("Nov")) month=Calendar.NOVEMBER;
            else if(mmm.equalsIgnoreCase("Dec")) month=Calendar.DECEMBER;
            else throw new IllegalArgumentException("Unknown month: "+mmm);

            // space
            if(line.charAt(3)!=' ') throw new IllegalArgumentException("Unexpected character at position 3: "+line.charAt(3));

            // day
            int day=Integer.parseInt(line.substring(4,6).trim());

            // space
            if(line.charAt(6)!=' ') throw new IllegalArgumentException("Unexpected character at position 6: "+line.charAt(6));

            // space
            if(line.charAt(15)!=' ') throw new IllegalArgumentException("Unexpected character at position 15: "+line.charAt(15));

            // Figure out the currect year
            Calendar cal=Calendar.getInstance();
            int year=cal.get(Calendar.YEAR);
            int curMonth=cal.get(Calendar.MONTH);
            if(month>curMonth) year--;
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            Date date=cal.getTime();

            /*
             * Host
             */
            int pos=line.indexOf(' ', 16);
            if(pos==-1) throw new IllegalArgumentException("Unable to find space after host");
            String host=line.substring(16, pos);
            if(host.length()==0) throw new IllegalArgumentException("Empty host");

            /*
             * Protocol
             */
            int pidPos=line.indexOf('[', pos+1);
            if(pidPos==-1) throw new IllegalArgumentException("Unable to find '[' after protocol");
            String protocol=line.substring(pos+1, pidPos);
            if(protocol.length()==0) throw new IllegalArgumentException("Empty protocol");

            /*
             * PID
             */
            int pidEnd=line.indexOf(']', pidPos+1);
            if(pidEnd==-1) throw new IllegalArgumentException("Unable to find ']' after PID");
            int pid=Integer.parseInt(line.substring(pidPos+1, pidEnd));

            /*
             * colon, space
             */
            if(
                line.length()<=(pidEnd+1) || line.charAt(pidEnd+1)!=':'
                || line.length()<=(pidEnd+2) || line.charAt(pidEnd+2)!=' '
            ) throw new IllegalArgumentException("A colon, space ': ' does not immediately follow the PID");

            if(protocol.equals(SmtpEntry.PROTOCOL)) {
                /*
                 * Message ID
                 */
                int messageIDEnd=line.indexOf(':', pidEnd+3);
                if(messageIDEnd==-1) throw new IllegalArgumentException("Unable to find the colon ':' following the message ID");
                String messageID=line.substring(pidEnd+3, messageIDEnd);
                if(messageID.length()==0) throw new IllegalArgumentException("Empty message ID");

                /*
                 * from, to, or forward?
                 */
                line=line.substring(messageIDEnd+2);
                if(line.length()>5 && line.substring(0, 5).equals("from=")) {
                    /*
                     * Parse the remaining
                     */
                    String from=null;
                    int size=-1;
                    String relay=null;
                    String[] exprs=StringUtility.splitString(line, ',');
                    for(int c=0;c<exprs.length;c++) {
                        String expr=exprs[c].trim();
                        int equalPos=expr.indexOf('=');
                        // This parsing does not include the second part of a multi-part to field
                        if(equalPos!=-1) {
                            String key=expr.substring(0, equalPos);
                            if(key.equals("from")) {
                                if(from!=null) throw new IllegalArgumentException("from found twice");
                                from=expr.substring(equalPos+1).trim();
                                if(from.length()>0 && from.charAt(0)=='\\') from=from.substring(1);
                                if(from.length()==0) throw new IllegalArgumentException("Empty from");
                            } else if(key.equals("size")) {
                                if(size!=-1) throw new IllegalArgumentException("size found twice");
                                size=Integer.parseInt(expr.substring(equalPos+1).trim());
                                if(size<0) throw new IllegalArgumentException("Invalid size: "+size);
                            } else if(key.equals("relay")) {
                                if(relay!=null) throw new IllegalArgumentException("relay found twice");
                                relay=expr.substring(equalPos+1).trim();
                                if(relay.length()==0) throw new IllegalArgumentException("Empty relay");
                            }
                        }
                    }

                    if(from==null) throw new IllegalArgumentException("from not found");
                    if(size==-1) throw new IllegalArgumentException("size not found");

                    /*
                     * Return the new object
                     */
                    return new SmtpFromEntry(date, host, pid, messageID, from, size, relay);
                } else if(line.length()>3 && line.substring(0, 3).equals("to=")) {
                    /*
                     * Parse the remaining
                     */
                    String to=null;
                    String ctladdr=null;
                    String mailer=null;
                    String relay=null;
                    String[] exprs=StringUtility.splitString(line, ',');
                    for(int c=0;c<exprs.length;c++) {
                        String expr=exprs[c].trim();
                        int equalPos=expr.indexOf('=');
                        // Skipping some of the details by not processing without =
                        if(equalPos!=-1) {
                            String key=expr.substring(0, equalPos);
                            if(key.equals("to")) {
                                if(to!=null) throw new IllegalArgumentException("to found twice");
                                to=expr.substring(equalPos+1).trim();
                                if(to.length()>0 && to.charAt(0)=='\\') to=to.substring(1);
                                if(to.length()==0) throw new IllegalArgumentException("Empty to");
                            } else if(key.equals("ctladdr")) {
                                if(ctladdr!=null) throw new IllegalArgumentException("ctladdr found twice");
                                ctladdr=expr.substring(equalPos+1).trim();
                                if(ctladdr.length()>0 && ctladdr.charAt(0)=='\\') ctladdr=ctladdr.substring(1);
                                if(ctladdr.length()==0) throw new IllegalArgumentException("Empty ctladdr");
                            } else if(key.equals("mailer")) {
                                if(mailer!=null) throw new IllegalArgumentException("mailer found twice");
                                mailer=expr.substring(equalPos+1).trim();
                                if(mailer.length()==0) throw new IllegalArgumentException("Empty mailer");
                            } else if(key.equals("relay")) {
                                if(relay!=null) throw new IllegalArgumentException("relay found twice");
                                relay=expr.substring(equalPos+1).trim();
                                if(relay.length()==0) throw new IllegalArgumentException("Empty relay");
                            }
                        }
                    }

                    if(to==null) throw new IllegalArgumentException("to not found");
                    if(mailer==null) throw new IllegalArgumentException("mailer not found");

                    /*
                     * Return the new object
                     */
                    return new SmtpToEntry(date, host, pid, messageID, to, ctladdr, mailer, relay);
                } else return null;
            } else return null;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    protected Date date;
    protected String host;
    protected int pid;

    protected MailLogEntry(Date date, String host, int pid) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MailLogEntry.class, "<init>(Date,String,int)", null);
        try {
            this.date=date;
            this.host=host;
            this.pid=pid;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    final public Date getDate() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MailLogEntry.class, "getDate()", null);
        try {
            return date;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    final public String getHost() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MailLogEntry.class, "getHost()", null);
        try {
            return host;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    final public int getPID() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MailLogEntry.class, "getPID()", null);
        try {
            return pid;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public abstract String getProtocol();

    public String toString() {
        Profiler.startProfile(Profiler.FAST, MailLogEntry.class, "toString()", null);
        try {
            return getClass().getName()+": date="+SQLUtility.getDate(date.getTime())+", host="+host+", pid="+pid+", protocol="+getProtocol();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}