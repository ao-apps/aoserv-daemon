/*
 * Copyright 2000-2013, 2017, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.util.ErrorPrinter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Uses the information available in /proc to determine the number of active PostgreSQL connections.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresCount extends DBReportData {

	private static final File proc = new File("/proc");

	public PostgresCount() throws IOException, SQLException {
		int total=0;
		// Only the outer-most server counts the postgres processes
		if(AOServDaemon.getThisServer().getFailoverServer() == null) {
			String[] list=proc.list();
			int len=list.length;
			for(int c=0;c<len;c++) {
				String filename=list[c];
				char ch=filename.charAt(0);
				if(ch>='0' && ch<='9') {
					File file=new File(proc, filename);
					if(file.isDirectory()) {
						try {
							try (FileInputStream in = new FileInputStream(new File(file, "cmdline"))) {
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
							}
						} catch(FileNotFoundException err) {
							// Normal, if process has exited
						}
					}
				}
			}
		}
		numUsers=total;
	}

	public static void main(String[] args) {
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
	}
}
