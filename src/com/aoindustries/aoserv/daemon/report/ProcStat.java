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
import java.util.*;

/**
 * Encapsulates the output of the /proc/stat file.
 *
 * @author  AO Industries, Inc.
 */
final public class ProcStat {

    public final long[]
        userCPUTimes,
        niceCPUTimes,
        sysCPUTimes
    ;

    public final long
        pagesIn,
        pagesOut
    ;

    public final long
        swapsIn,
        swapsOut
    ;

    public final long
        contextSwitches,
        processes
    ;

    public ProcStat() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ProcStat.class, "<init>()", null);
        try {
            List<Long> userCPUTimes=new ArrayList<Long>();
            List<Long> niceCPUTimes=new ArrayList<Long>();
            List<Long> sysCPUTimes=new ArrayList<Long>();
            long pagesIn=0;
            long pagesOut=0;
            long swapsIn=0;
            long swapsOut=0;
            long contextSwitches=0;
            long processes=0;

            // Only the outer-most server tracks these stats
            if(AOServDaemon.getThisAOServer().getFailoverServer()==null) {
                // Parse for the values
                BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")));
                try {
                    String line;
                    while((line=in.readLine())!=null) {
                        String[] words=StringUtility.splitString(line);
                        String label=words[0];
                        if(
                            label.length()>3
                            && label.startsWith("cpu")
                        ) {
                            userCPUTimes.add(Long.valueOf(words[1])*10);
                            niceCPUTimes.add(Long.valueOf(words[2])*10);
                            sysCPUTimes.add(Long.valueOf(words[3])*10);
                        } else if(label.equals("page")) {
                            pagesIn=Long.parseLong(words[1]);
                            pagesOut=Long.parseLong(words[2]);
                        } else if(label.equals("swap")) {
                            swapsIn=Long.parseLong(words[1]);
                            swapsOut=Long.parseLong(words[2]);
                        } else if(label.equals("ctxt")) {
                            contextSwitches=Long.parseLong(words[1]);
                        } else if(label.equals("processes")) {
                            processes=Long.parseLong(words[1]);
                        }
                    }
                } finally {
                    in.close();
                }
            }

            // Copy into instance
            this.userCPUTimes=getLongArray(userCPUTimes);
            this.niceCPUTimes=getLongArray(niceCPUTimes);
            this.sysCPUTimes=getLongArray(sysCPUTimes);
            this.pagesIn=pagesIn;
            this.pagesOut=pagesOut;
            this.swapsIn=swapsIn;
            this.swapsOut=swapsOut;
            this.contextSwitches=contextSwitches;
            this.processes=processes;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void main(String[] args) {
        Profiler.startProfile(Profiler.UNKNOWN, ProcStat.class, "main(String[])", null);
        try {
            try {
                System.err.println(new ProcStat());
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

    private static long[] getLongArray(List<Long> list) {
        Profiler.startProfile(Profiler.UNKNOWN, ProcStat.class, "getLongArray(List<Long>)", null);
        try {
            int len=list.size();
            long[] la=new long[len];
            for(int c=0;c<len;c++) la[c]=list.get(c).longValue();
            return la;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /*
    private static int[] getIntArray(ArrayList list) {
        Profiler.startProfile(Profiler.UNKNOWN, ProcStat.class, "getIntArray(ArrayList)", null);
        try {
            int len=list.size();
            int[] ia=new int[len];
            for(int c=0;c<len;c++) ia[c]=((Integer)list.get(c)).intValue();
            return ia;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }*/

    public String toString() {
        Profiler.startProfile(Profiler.UNKNOWN, ProcStat.class, "toString()", null);
        try {
            StringBuilder SB=new StringBuilder();
            SB.append(getClass().getName());
            for(int c=0;c<userCPUTimes.length;c++) {
                SB
                    .append(c==0?'?':'&')
                    .append("cpu")
                    .append(c)
                    .append("=(user=")
                    .append(userCPUTimes[c])
                    .append(",nice=")
                    .append(niceCPUTimes[c])
                    .append(",sys=")
                    .append(sysCPUTimes[c])
                    .append(')')
                ;
            }
            SB.append("&pages=(in=").append(pagesIn).append(",out=").append(pagesOut).append(')');
            SB.append("&swaps=(in=").append(swapsIn).append(",out=").append(swapsOut).append(')');
            SB.append("&contexts=").append(contextSwitches);
            SB.append("&processes=").append(processes);
            return SB.toString();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}