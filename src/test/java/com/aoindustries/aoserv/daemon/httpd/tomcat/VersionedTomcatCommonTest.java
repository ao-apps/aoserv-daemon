/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2021, 2022  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class VersionedTomcatCommonTest {

	@Test
	public void testVersion1() {
		VersionedTomcatCommon.Version version1 = new VersionedTomcatCommon.Version(
			new PackageManager.Version("9.0.53"),
			new PackageManager.Version("1")
		);
		assertEquals(0, version1.compareTo("9.0.53-1"));
		assertTrue(version1.compareTo("9.0.53-2") < 0);
	}

	@Test
	public void testVersion2() {
		VersionedTomcatCommon.Version version2 = new VersionedTomcatCommon.Version(
			new PackageManager.Version("9.0.53"),
			new PackageManager.Version("2")
		);
		assertTrue(version2.compareTo("9.0.53-1") > 0);
		assertEquals(0, version2.compareTo("9.0.53-2"));
		assertTrue(version2.compareTo("9.0.53-3") < 0);
	}
}
