/*
 * Copyright 2000-2013, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.util.ErrorPrinter;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.sql.SQLException;

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
	}

	public static void main(String[] args) {
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
	}

	@Override
	public String toString() {
		return
			getClass().getName()
			+"?numUsers="+numUsers
			+"&load="+load
		;
	}
}
