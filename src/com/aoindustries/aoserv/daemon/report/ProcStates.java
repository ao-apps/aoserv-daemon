/*
 * Copyright 2000-2013, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.aoserv.client.linux.LinuxId;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import com.aoindustries.validation.ValidationException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;

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

	public ProcStates() throws IOException, SQLException {
		int
			_total_sleep=0,
			_user_sleep=0,
			_total_run=0,
			_user_run=0,
			_total_zombie=0,
			_user_zombie=0,
			_total_trace=0,
			_user_trace=0,
			_total_uninterruptible=0,
			_user_uninterruptible=0,
			_total_unknown=0,
			_user_unknown=0
		;

		Server thisAoServer = AOServDaemon.getThisAOServer();
		boolean isOuterServer = thisAoServer.getFailoverServer()==null;
		int uid_min = thisAoServer.getUidMin().getId();

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
								String[] words= StringUtility.splitString(line);
								uid=Integer.parseInt(words[1]);
							}
						}
						if(isOuterServer) {
							if(state==null) _total_unknown++;
							else {
								ch=state.charAt(0);
								if(ch=='S') _total_sleep++;
								else if(ch=='R') _total_run++;
								else if(ch=='Z') _total_zombie++;
								else if(ch=='T') _total_trace++;
								else if(ch=='D') _total_uninterruptible++;
								else _total_unknown++;
							}
						}
						if(
							uid >= uid_min
							&& thisAoServer.getLinuxServerAccount(LinuxId.valueOf(uid))!=null
						) {
							if(state==null) _user_unknown++;
							else {
								ch=state.charAt(0);
								if(ch=='S') _user_sleep++;
								else if(ch=='R') _user_run++;
								else if(ch=='Z') _user_zombie++;
								else if(ch=='T') _user_trace++;
								else if(ch=='D') _user_uninterruptible++;
								else _user_unknown++;
							}
						}
						in.close();
					} catch(FileNotFoundException err) {
						// Normal if the process has terminated
					} catch(ValidationException e) {
						throw new IOException(e);
					}
				}
			}
		}

		// Copy into instance
		this.total_sleep=_total_sleep;
		this.user_sleep=_user_sleep;
		this.total_run=_total_run;
		this.user_run=_user_run;
		this.total_zombie=_total_zombie;
		this.user_zombie=_user_zombie;
		this.total_trace=_total_trace;
		this.user_trace=_user_trace;
		this.total_uninterruptible=_total_uninterruptible;
		this.user_uninterruptible=_user_uninterruptible;
		this.total_unknown=_total_unknown;
		this.user_unknown=_user_unknown;
	}

	public static void main(String[] args) {
		try {
			System.err.println(new ProcStates());
			System.exit(0);
		} catch(IOException err) {
			ErrorPrinter.printStackTraces(err);
			System.exit(1);
		} catch(SQLException err) {
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
