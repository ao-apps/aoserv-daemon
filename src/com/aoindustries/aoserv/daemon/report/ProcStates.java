package com.aoindustries.aoserv.daemon.report;

/*
 * Copyright 2000-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.validator.LinuxID;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Encapsulates the output of the /proc/<I>PID</I>/status files.
 *
 * @author  AO Industries, Inc.
 */
final public class ProcStates {

    private static final File proc=new File("/proc");

    public final int
        total_sleep,
        user_sleep,
        total_run,
        user_run,
        total_zombie,
        user_zombie,
        total_trace,
        user_trace,
        total_uninterruptible,
        user_uninterruptible,
        total_unknown,
        user_unknown
    ;

    public ProcStates() throws IOException, ValidationException {
        int
            total_sleep0=0,
            user_sleep0=0,
            total_run0=0,
            user_run0=0,
            total_zombie0=0,
            user_zombie0=0,
            total_trace0=0,
            user_trace0=0,
            total_uninterruptible0=0,
            user_uninterruptible0=0,
            total_unknown0=0,
            user_unknown0=0
        ;

        AOServer aoServer=AOServDaemon.getThisAOServer();
        boolean isOuterServer=aoServer.getFailoverServer()==null;

        // Parse for the values
        String[] list=proc.list();
        int len=list.length;
        for(int c=0;c<len;c++) {
            String filename=list[c];
            char ch=filename.charAt(0);
            if(ch>='0' && ch<='9') {
                File file=new File(proc, filename);
                if(file.isDirectory()) {
                    try {
                        String state=null;
                        int uid=-1;
                        BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(new File(file, "status"))));
                        String line;
                        while((state==null || uid==-1) && (line=in.readLine())!=null) {
                            if(line.startsWith("State:")) {
                                String[] words=StringUtility.splitString(line);
                                state=words[1];
                            } else if(line.startsWith("Uid:")) {
                                String[] words=StringUtility.splitString(line);
                                uid=Integer.parseInt(words[1]);
                            }
                        }
                        if(isOuterServer) {
                            if(state==null) total_unknown0++;
                            else {
                                ch=state.charAt(0);
                                if(ch=='S') total_sleep0++;
                                else if(ch=='R') total_run0++;
                                else if(ch=='Z') total_zombie0++;
                                else if(ch=='T') total_trace0++;
                                else if(ch=='D') total_uninterruptible0++;
                                else total_unknown0++;
                            }
                        }
                        if(
                            uid>=UnixFile.MINIMUM_USER_UID
                            && !aoServer.getLinuxAccounts(LinuxID.valueOf(uid)).isEmpty()
                        ) {
                            if(state==null) user_unknown0++;
                            else {
                                ch=state.charAt(0);
                                if(ch=='S') user_sleep0++;
                                else if(ch=='R') user_run0++;
                                else if(ch=='Z') user_zombie0++;
                                else if(ch=='T') user_trace0++;
                                else if(ch=='D') user_uninterruptible0++;
                                else user_unknown0++;
                            }
                        }
                        in.close();
                    } catch(FileNotFoundException err) {
                        // Normal if the process has terminated
                    }
                }
            }
        }

        // Copy into instance
        this.total_sleep=total_sleep0;
        this.user_sleep=user_sleep0;
        this.total_run=total_run0;
        this.user_run=user_run0;
        this.total_zombie=total_zombie0;
        this.user_zombie=user_zombie0;
        this.total_trace=total_trace0;
        this.user_trace=user_trace0;
        this.total_uninterruptible=total_uninterruptible0;
        this.user_uninterruptible=user_uninterruptible0;
        this.total_unknown=total_unknown0;
        this.user_unknown=user_unknown0;
    }

    public static void main(String[] args) {
        try {
            System.err.println(new ProcStates());
            System.exit(0);
        } catch(IOException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(1);
        } catch(ValidationException err) {
            ErrorPrinter.printStackTraces(err);
            System.exit(2);
        }
    }

    @Override
    public String toString() {
        return
            getClass().getName()
            +"?total_sleep="+total_sleep
            +"&user_sleep="+user_sleep
            +"&total_run="+total_run
            +"&user_run="+user_run
            +"&total_zombie="+total_zombie
            +"&user_zombie="+user_zombie
            +"&total_trace="+total_trace
            +"&user_trace="+user_trace
            +"&total_uninterruptible="+total_uninterruptible
            +"&user_uninterruptible="+user_uninterruptible
            +"&total_unknown="+total_unknown
            +"&user_unknown="+user_unknown
        ;
    }
}