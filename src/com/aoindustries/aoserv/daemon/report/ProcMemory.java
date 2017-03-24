/*
 * Copyright 2000-2013, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.report;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;

/**
 * Encapsulates the output of the /proc/meminfo file.
 *
 * @author  AO Industries, Inc.
 */
final public class ProcMemory {

	public final int
		mem_total,
		mem_free,
		mem_shared,
		buffers,
		cached,
		swap_cached,
		active,
		inact_dirty,
		inact_clean,
		inact_target,
		high_total,
		high_free,
		low_total,
		low_free
	;

	public ProcMemory() throws IOException, SQLException {
		int
			_mem_total=0,
			_mem_free=0,
			_mem_shared=0,
			_buffers=0,
			_cached=0,
			_swap_cached=0,
			_active=0,
			_inact_dirty=0,
			_inact_clean=0,
			_inact_target=0,
			_high_total=0,
			_high_free=0,
			_low_total=0,
			_low_free=0
		;

		// Only the outer-most server tracks memory use
		if(AOServDaemon.getThisAOServer().getFailoverServer()==null) {
			// Parse for the values
			BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")));
			try {
				String line;
				while((line=in.readLine())!=null) {
					String[] words = StringUtility.splitString(line);
					String label=words[0];
					if(label.equals("MemTotal:")) _mem_total=Integer.parseInt(words[1]);
					else if(label.equals("MemFree:")) _mem_free=Integer.parseInt(words[1]);
					else if(label.equals("MemShared:")) _mem_shared=Integer.parseInt(words[1]);
					else if(label.equals("Buffers:")) _buffers=Integer.parseInt(words[1]);
					else if(label.equals("Cached:")) _cached=Integer.parseInt(words[1]);
					else if(label.equals("SwapCached:")) _swap_cached=Integer.parseInt(words[1]);
					else if(label.equals("Active:")) _active=Integer.parseInt(words[1]);
					else if(label.equals("Inact_dirty:")) _inact_dirty=Integer.parseInt(words[1]);
					else if(label.equals("Inact_clean:")) _inact_clean=Integer.parseInt(words[1]);
					else if(label.equals("Inact_target:")) _inact_target=Integer.parseInt(words[1]);
					else if(label.equals("HighTotal:")) _high_total=Integer.parseInt(words[1]);
					else if(label.equals("HighFree:")) _high_free=Integer.parseInt(words[1]);
					else if(label.equals("LowTotal:")) _low_total=Integer.parseInt(words[1]);
					else if(label.equals("LowFree:")) _low_free=Integer.parseInt(words[1]);
				}
			} finally {
				in.close();
			}
		}

		// Copy into instance
		this.mem_total=_mem_total;
		this.mem_free=_mem_free;
		this.mem_shared=_mem_shared;
		this.buffers=_buffers;
		this.cached=_cached;
		this.swap_cached=_swap_cached;
		this.active=_active;
		this.inact_dirty=_inact_dirty;
		this.inact_clean=_inact_clean;
		this.inact_target=_inact_target;
		this.high_total=_high_total;
		this.high_free=_high_free;
		this.low_total=_low_total;
		this.low_free=_low_free;
	}

	public static void main(String[] args) {
		try {
			System.err.println(new ProcMemory());
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
			+ "?mem_total="+mem_total
			+ "&mem_free="+mem_free
			+ "&mem_shared="+mem_shared
			+ "&buffers="+buffers
			+ "&cached="+cached
			+ "&swap_cached="+swap_cached
			+ "&active="+active
			+ "&inact_dirty="+inact_dirty
			+ "&inact_clean="+inact_clean
			+ "&inact_target="+inact_target
			+ "&high_total="+high_total
			+ "&high_free="+high_free
			+ "&low_total="+low_total
			+ "&low_free="+low_free
		;
	}
}
