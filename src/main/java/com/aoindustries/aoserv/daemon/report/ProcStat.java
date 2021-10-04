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

import com.aoapps.lang.Strings;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the output of the /proc/stat file.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcStat {

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
		List<Long> _userCPUTimes=new ArrayList<>();
		List<Long> _niceCPUTimes=new ArrayList<>();
		List<Long> _sysCPUTimes=new ArrayList<>();
		long _pagesIn=0;
		long _pagesOut=0;
		long _swapsIn=0;
		long _swapsOut=0;
		long _contextSwitches=0;
		long _processes=0;

		// Only the outer-most server tracks these stats
		if(AOServDaemon.getThisServer().getFailoverServer() == null) {
			// Parse for the values
			try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")))) {
				String line;
				while((line=in.readLine())!=null) {
					String[] words=Strings.split(line);
					String label=words[0];
					if(
						label.length()>3
						&& label.startsWith("cpu")
					) {
						_userCPUTimes.add(Long.valueOf(words[1])*10);
						_niceCPUTimes.add(Long.valueOf(words[2])*10);
						_sysCPUTimes.add(Long.valueOf(words[3])*10);
					} else if(label.equals("page")) {
						_pagesIn=Long.parseLong(words[1]);
						_pagesOut=Long.parseLong(words[2]);
					} else if(label.equals("swap")) {
						_swapsIn=Long.parseLong(words[1]);
						_swapsOut=Long.parseLong(words[2]);
					} else if(label.equals("ctxt")) {
						_contextSwitches=Long.parseLong(words[1]);
					} else if(label.equals("processes")) {
						_processes=Long.parseLong(words[1]);
					}
				}
			}
		}

		// Copy into instance
		this.userCPUTimes=getLongArray(_userCPUTimes);
		this.niceCPUTimes=getLongArray(_niceCPUTimes);
		this.sysCPUTimes=getLongArray(_sysCPUTimes);
		this.pagesIn=_pagesIn;
		this.pagesOut=_pagesOut;
		this.swapsIn=_swapsIn;
		this.swapsOut=_swapsOut;
		this.contextSwitches=_contextSwitches;
		this.processes=_processes;
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void main(String[] args) {
		try {
			System.err.println(new ProcStat());
			System.exit(0);
		} catch(IOException err) {
			ErrorPrinter.printStackTraces(err, System.err);
			System.exit(1);
		} catch(SQLException err) {
			ErrorPrinter.printStackTraces(err, System.err);
			System.exit(2);
		}
	}

	private static long[] getLongArray(List<Long> list) {
		int len=list.size();
		long[] la=new long[len];
		for(int c=0;c<len;c++) la[c]=list.get(c);
		return la;
	}

	/*
	private static int[] getIntArray(ArrayList list) {
		int len=list.size();
		int[] ia=new int[len];
		for(int c=0;c<len;c++) ia[c]=((Integer)list.get(c)).intValue();
		return ia;
	}*/

	@Override
	public String toString() {
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
	}
}
