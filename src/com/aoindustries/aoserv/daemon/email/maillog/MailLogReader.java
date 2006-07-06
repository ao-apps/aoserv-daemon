package com.aoindustries.aoserv.daemon.email.maillog;

/*
 * Copyright 2002-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.Package;
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.email.*;
import com.aoindustries.io.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Reads the mailog file, adding statistics to <code>SmtpStatManager</code>.
 *
 * @see  SmtpStatManager
 *
 * @author  AO Industries, Inc.
 */
public class MailLogReader implements Runnable {

    public static final String
        //REDHAT_MAILLOG="/var/log/maillog",
        MANDRAKE_MAILLOG="/var/log/mail/info"
    ;

    private MailLogReader() {
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, MailLogReader.class, "run()", null);
        try {
            while(true) {
                try {
                    String maillog;
                    int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
                    //if(osv==OperatingSystemVersion.REDHAT_7_2_I686) maillog=REDHAT_MAILLOG;
                    if(
                        osv==OperatingSystemVersion.MANDRAKE_10_1_I586
                    ) maillog=MANDRAKE_MAILLOG;
                    else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    BufferedReader in=new BufferedReader(new InputStreamReader(new LogFollower(maillog)));
		    try {
			String line;
			while((line=in.readLine())!=null) {
			    try {
				MailLogEntry entry=MailLogEntry.parseLine(line);
				if(entry!=null) {
				    if(entry instanceof SmtpEntry) SmtpStatManager.processMailLogEntry((SmtpEntry)entry);
                                    // additional dispatching of mail log entries may occur here
				}
			    } catch(IllegalArgumentException err) {
				System.err.println("MailLogReader: Unable to parse line ("+err.getMessage()+"): "+line);
			    }
			}
		    } finally {
			in.close();
		    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(60000);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static boolean started=false;

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MailLogReader.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(MailLogReader.class) && !AOServDaemon.getThisAOServer().isQmail()) {
                if(!started) {
                    synchronized(System.out) {
                        if(!started) {
                            System.out.print("Starting MailLogReader: ");
                            new Thread(new MailLogReader(), "MailLogReader").start();
                            System.out.println("Done");
                            started=true;
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
