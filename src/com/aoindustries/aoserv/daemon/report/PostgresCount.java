package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2006 by AO Industries, Inc.,
 * 2200 Dogwood Ct N, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.profiler.*;
import com.aoindustries.sql.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;

/**
 * Uses the information available in /proc to determine the number of active PostgreSQL connections.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresCount extends DBReportData {

    private static final File proc=new File("/proc");

    public PostgresCount() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresCount.class, "<init>()", null);
        try {
            int total=0;
            // Only the outer-most server counts the postgres processes
            if(AOServDaemon.getThisAOServer().getFailoverServer()==null) {
                String[] list=proc.list();
                int len=list.length;
                for(int c=0;c<len;c++) {
                    String filename=list[c];
                    char ch=filename.charAt(0);
                    if(ch>='0' && ch<='9') {
                        File file=new File(proc, filename);
                        if(file.isDirectory()) {
                            try {
                                FileInputStream in=new FileInputStream(new File(file, "cmdline"));
                                try {
                                    if(
                                        in.read()=='/'
                                        && in.read()=='u'
                                        && in.read()=='s'
                                        && in.read()=='r'
                                        && in.read()=='/'
                                        && in.read()=='b'
                                        && in.read()=='i'
                                        && in.read()=='n'
                                        && in.read()=='/'
                                        && in.read()=='p'
                                        && in.read()=='o'
                                        && in.read()=='s'
                                        && in.read()=='t'
                                        && in.read()=='g'
                                        && in.read()=='r'
                                        && in.read()=='e'
                                        && in.read()=='s'
                                    ) total++;
                                } finally {
                                    in.close();
                                }
                            } catch(FileNotFoundException err) {
                                // Normal, if process has exited
                            }
                        }
                    }
                }
            }
            numUsers=total;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void main(String[] args) {
        Profiler.startProfile(Profiler.UNKNOWN, PostgresCount.class, "main(String[])", null);
        try {
            try {
                System.err.println(new PostgresCount());
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
}