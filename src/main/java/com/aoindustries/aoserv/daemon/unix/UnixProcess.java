/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2009, 2017, 2018, 2020  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.unix;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A <code>UnixProcess</code> represents a process
 * running on any Unix machine.
 *
 * @author  AO Industries, Inc.
 */
abstract public class UnixProcess {

	private static final Logger logger = Logger.getLogger(UnixProcess.class.getName());

	protected int pid;

	/**
	 * Constructs a Unix process given its process ID.
	 */
	public UnixProcess(int pid) {
		this.pid=pid;
	}

	/**
	 * Determines the group ID of a process.  The subclasses of
	 * <code>UnixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>UnixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	abstract public int getGid() throws IOException;

	/**
	 * Determines the user ID of a process.  The subclasses of
	 * <code>UnixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>UnixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	abstract public int getUid() throws IOException;

	/**
	 * Determines if the process is currently running.  The subclasses of
	 * <code>UnixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>UnixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	abstract public boolean isRunning() throws IOException;

	/**
	 * Kills this process.  Sends a term signal once, waits two seconds,
	 * then sends a kill signal.  The signals are sent to the execution of the
	 * <code>/bin/kill</code> executable.
	 */
	public void killProc() throws IOException {
		String pidS=String.valueOf(pid);
		if(isRunning()) {
			AOServDaemon.exec("/bin/kill", "-SIGTERM", pidS);
		}
		if(isRunning()) {
			try {
				Thread.sleep(2000);
			} catch(InterruptedException err) {
				logger.log(Level.WARNING, null, err);
			}
		}
		if(isRunning()) {
			AOServDaemon.exec("/bin/kill", "-SIGKILL", pidS);
		}
	}

	/**
	 * Sends a signal to this process.  The signals are sent to the execution of the
	 * <code>/bin/kill</code> executable.
	 */
	public void signal(String signalName) throws IOException {
		AOServDaemon.exec(
			"/bin/kill",
			"-"+signalName,
			Integer.toString(pid)
		);
	}
}
