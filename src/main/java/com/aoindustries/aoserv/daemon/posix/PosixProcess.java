/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2009, 2017, 2018, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.posix;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;

/**
 * A <code>PosixProcess</code> represents a process
 * running on any POSIX machine.
 *
 * @author  AO Industries, Inc.
 */
public abstract class PosixProcess {

	protected int pid;

	/**
	 * Constructs a POSIX process given its process ID.
	 */
	protected PosixProcess(int pid) {
		this.pid=pid;
	}

	/**
	 * Determines the group ID of a process.  The subclasses of
	 * <code>PosixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>PosixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	public abstract int getGid() throws IOException;

	/**
	 * Determines the user ID of a process.  The subclasses of
	 * <code>PosixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>PosixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	public abstract int getUid() throws IOException;

	/**
	 * Determines if the process is currently running.  The subclasses of
	 * <code>PosixProcess</code> must implement this functionality.  Calling
	 * the method on a <code>PosixProcess</code> will result in an
	 * <code>IOException</code>.
	 */
	public abstract boolean isRunning() throws IOException;

	/**
	 * Kills this process.  Sends a term signal once, waits two seconds,
	 * then sends a kill signal.  The signals are sent to the execution of the
	 * <code>/bin/kill</code> executable.
	 */
	public void killProc() throws IOException, InterruptedException {
		String pidS = String.valueOf(pid);
		if(isRunning()) {
			AOServDaemon.exec("/bin/kill", "-SIGTERM", pidS);
			Thread.sleep(100);
		}
		if(isRunning()) {
			Thread.sleep(1900);
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
