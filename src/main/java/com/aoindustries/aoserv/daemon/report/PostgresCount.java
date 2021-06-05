/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2017, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoapps.lang.util.ErrorPrinter;
import com.aoindustries.aoserv.daemon.AOServDaemon;
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

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void main(String[] args) {
		try {
			System.err.println(new PostgresCount());
			System.exit(0);
		} catch(IOException err) {
			ErrorPrinter.printStackTraces(err, System.err);
			System.exit(1);
		} catch(SQLException err) {
			ErrorPrinter.printStackTraces(err, System.err);
			System.exit(2);
		}
	}
}
