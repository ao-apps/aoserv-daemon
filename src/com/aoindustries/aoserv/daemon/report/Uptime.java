package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;

/**
 * Encapsulates the output of the /usr/bin/uptime command.
 *
 * @author  AO Industries, Inc.
 */
final public class Uptime {

    private static final String[] cmd={"/usr/bin/uptime"};

    final public int numUsers;
    final public float load;

    public Uptime() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, Uptime.class, "<init>()", null);
        try {
            String line;
            Process P=Runtime.getRuntime().exec(cmd);
            try {
                P.getOutputStream().close();
                BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
                try {
                    line=in.readLine();
                } finally {
                    in.close();
                }
            } finally {
                try {
                    int retCode=P.waitFor();
                    if(retCode!=0) throw new IOException("/usr/bin/uptime exited with non-zero status: "+retCode);
                } catch(InterruptedException err) {
                    InterruptedIOException ioErr=new InterruptedIOException();
                    ioErr.initCause(err);
                    throw ioErr;
                }
            }
            if(line==null) throw new EOFException("Nothing output by /usr/bin/uptime");

            // Find the third colon, then back two commas
            int pos=line.lastIndexOf(':');
            pos=line.lastIndexOf(',', pos-1);
            pos=line.lastIndexOf(',', pos-1)+1;

            // skip past spaces
            int len=line.length();
            while(pos<len && line.charAt(pos)==' ') pos++;

            // find next space
            int pos2=pos+1;
            while(pos2<len && line.charAt(pos2)!=' ') pos2++;

            // Parse the number of users
            numUsers=Integer.parseInt(line.substring(pos, pos2));

            // Only the top-level server keeps track of load
            if(AOServDaemon.getThisAOServer().getFailoverServer()==null) {
                // Find the next colon
                pos=line.indexOf(':', pos2+1)+1;

                // Skip any whitespace
                while(pos<len && line.charAt(pos)==' ') pos++;

                // Find the next comma
                pos2=line.indexOf(',', pos);

                load=Float.parseFloat(line.substring(pos, pos2));
            } else load=0.00f;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void main(String[] args) {
        Profiler.startProfile(Profiler.UNKNOWN, Uptime.class, "main(String[])", null);
        try {
            try {
                System.err.println(new Uptime());
                System.exit(0);
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
                System.exit(1);
            } catch(SQLException err) {
                ErrorPrinter.printStackTraces(err);
                System.exit(2);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String toString() {
        Profiler.startProfile(Profiler.FAST, Uptime.class, "toString()", null);
        try {
            return
                getClass().getName()
                +"?numUsers="+numUsers
                +"&load="+load
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
}
