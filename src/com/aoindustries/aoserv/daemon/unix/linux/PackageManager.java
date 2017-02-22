/*
 * Copyright 2013, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix.linux;

import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.DirectoryMetaSnapshot;
import com.aoindustries.util.StringUtility;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Manages the set of packages on a server.
 *
 * @author  AO Industries, Inc.
 */
public class PackageManager {

	/**
	 * The set of all managed package names.  This is specified as an enum
	 * to avoid accidental removal of critical system RPMs from outside callers.
	 */
	public enum PackageName {
		APACHE_TOMCAT_3_1("apache-tomcat_3_1"),
		APACHE_TOMCAT_3_2("apache-tomcat_3_2"),
		APACHE_TOMCAT_4_1("apache-tomcat_4_1"),
		APACHE_TOMCAT_5_5("apache-tomcat_5_5"),
		APACHE_TOMCAT_6_0("apache-tomcat_6_0"),
		APACHE_TOMCAT_7_0("apache-tomcat_7_0"),
		APACHE_TOMCAT_8_0("apache-tomcat_8_0"),
		AOSERV_JILTER("aoserv-jilter"),
		CVS("cvs"),
		JBOSS_2_2_2("jboss_2_2_2"),
		MAJORDOMO("majordomo"),
		PHP_5_2("php_5_2-i686"),
		PHP_5_3("php_5_3-i686"),
		PHP_5_4("php_5_4-i686"),
		PHP_5_5("php_5_5-i686"),
		PHP_5_6("php_5_6-i686");

		private final String rpmName;
		// Only needed when trying to automatically remove packages: private final PackageName[] requires;
		private PackageName(String rpmName /* Only needed when trying to automatically remove packages: , PackageName... requires */) {
			this.rpmName = rpmName;
			// Only needed when trying to automatically remove packages: this.requires = requires;
		}
	}

	/**
	 * Enables debugging output.
	 */
	private static final boolean DEBUG = false;

	/**
	 * The path to some executables.
	 */
	private static final String
		RPM_EXE_PATH = "/bin/rpm",
		YUM_EXE_PATH = "/usr/bin/yum"
	;

	/**
	 * The directory containing the RPM files.  Used to detect possible changes
	 * to the installed RPMs.
	 */
	private static final String VAR_LIB_RPM = "/var/lib/rpm";

	/**
	 * Make no instances.
	 */
	private PackageManager() {
	}

	public static class Version implements Comparable<Version> {

		private static class Segment implements Comparable<Segment> {

			private static Segment[] parseSegments(String version) {
				final List<Segment> segments = new ArrayList<>();
				final StringBuilder buffer = new StringBuilder();
				boolean lastNumeric = false;
				final int len = version.length();
				for(int i=0; i<len; i++) {
					final char ch = version.charAt(i);
					if(
						(ch>='0' && ch<='9')
						|| (ch>='a' && ch<='z')
						|| (ch>='A' && ch<='Z')
					) {
						final boolean isNumeric = ch>='0' && ch<='9';
						if(buffer.length()>0 && lastNumeric!=isNumeric) {
							segments.add(new Segment(lastNumeric, buffer.toString()));
							buffer.setLength(0);
						}
						lastNumeric = isNumeric;
						buffer.append(ch);
					} else {
						if(buffer.length()>0) {
							segments.add(new Segment(lastNumeric, buffer.toString()));
							buffer.setLength(0);
						}
					}
				}
				if(buffer.length()>0) {
					segments.add(new Segment(lastNumeric, buffer.toString()));
				}
				return segments.toArray(new Segment[segments.size()]);
			}

			private final boolean numeric;
			private final String value;

			private Segment(boolean numeric, String value) {
				this.numeric = numeric;
				this.value = value;
			}

			@Override
			public String toString() {
				return value;
			}

			private boolean isNumeric() {
				return numeric;
			}

			private static String skipLeadingZeros(String value) {
				final int len = value.length();
				for(int i=0; i<len; i++) {
					if(value.charAt(i) != '0') return value.substring(i);
				}
				return "";
			}

			@Override
			public int compareTo(Segment other) {
				if(isNumeric()) {
					if(other.isNumeric()) {
						// Note: as unbounded size integers...
						final String s1 = skipLeadingZeros(value);
						final String s2 = skipLeadingZeros(other.value);
						if(s1.length() > s2.length()) return 1;
						if(s1.length() < s2.length()) return -1;
						return s1.compareTo(s2);
					} else {
						// one segment is alpha and the other is numeric, the numeric one is "larger"
						return 1;
					}
				} else {
					if(other.isNumeric()) {
						// one segment is alpha and the other is numeric, the numeric one is "larger"
						return -1;
					} else {
						return value.compareTo(other.value);
					}
				}
			}
		}

		private final String version;
		private final Segment[] segments;

		public Version(String version) {
			this.version = version;
			this.segments = Segment.parseSegments(version);
		}

		@Override
		public String toString() {
			return version;
		}

		@Override
		public int hashCode() {
			return version.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof Version)) return false;
			Version other = (Version)obj;
			return version.equals(other.version);
		}

		/**
		 * Compares two versions in segment order.
		 * Implement same logic as RPM:
		 * <a href="http://linux.derkeiler.com/Newsgroups/comp.os.linux.development.system/2005-12/msg00397.html">http://linux.derkeiler.com/Newsgroups/comp.os.linux.development.system/2005-12/msg00397.html</a>
		 */
		@Override
		public int compareTo(Version other) {
			final int len = Math.max(segments.length, other.segments.length);
			for(int i=0; i<len; i++) {
				if(i>=segments.length) {
					// the string with more segments is "newer".
					return 1;
				}
				if(i>=other.segments.length) {
					// the string with more segments is "newer".
					return -1;
				}
				int diff = segments[i].compareTo(other.segments[i]);
				if(diff != 0) return diff;
			}
			return 0;
		}
	}

	public enum Architecture {
		noarch,
		i386,
		i486,
		i586,
		i686,
		x86_64
	}

	public static class RPM implements Comparable<RPM> {

		private final String name;
		private final Integer epoch;
		private final Version version;
		private final Version release;
		private final Architecture architecture;

		private RPM(
			String name,
			Integer epoch,
			Version version,
			Version release,
			Architecture architecture
		) {
			this.name = name;
			this.epoch = epoch;
			this.version = version;
			this.release = release;
			this.architecture = architecture;
		}

		@Override
		public String toString() {
			if(epoch==null) {
				if(architecture==null) return name+'-'+version+'-'+release;
				else return name+'-'+version+'-'+release+'.'+architecture;
			} else {
				if(architecture==null) return name+'-'+epoch+':'+version+'-'+release;
				else return name+'-'+epoch+':'+version+'-'+release+'.'+architecture;
			}
		}

		@Override
		public int hashCode() {
			int hash = name.hashCode();
			hash = hash*31 + Objects.hashCode(epoch);
			hash = hash*31 + version.hashCode();
			hash = hash*31 + release.hashCode();
			hash = hash*31 + Objects.hashCode(architecture);
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof RPM)) return false;
			RPM other = (RPM)obj;
			return
				name.equals(other.name)
				&& Objects.equals(epoch, other.epoch)
				&& version.equals(other.version)
				&& release.equals(other.release)
				&& Objects.equals(architecture, other.architecture)
			;
		}

		@Override
		public int compareTo(RPM other) {
			int diff = name.compareTo(other.name);
			if(diff != 0) return diff;
			int e1 = epoch==null ? 0 : epoch;
			int e2 = other.epoch==null ? 0 : other.epoch;
			if(e1 < e2) return -1;
			if(e1 > e2) return 1;
			diff = version.compareTo(other.version);
			if(diff != 0) return diff;
			diff = release.compareTo(other.release);
			if(diff != 0) return diff;
			if(architecture!=null) {
				if(other.architecture!=null) return architecture.compareTo(other.architecture);
				return -1;
			} else {
				if(other.architecture!=null) return 1;
				return 0;
			}
		}

		public String getName() {
			return name;
		}

		public Version getVersion() {
			return version;
		}

		public Version getRelease() {
			return release;
		}

		public Architecture getArchitecture() {
			return architecture;
		}

		/**
		 * Removes this package.
		 */
		public void remove() throws IOException {
			if(DEBUG) {
				final PrintStream out = System.out;
				synchronized(out) {
					out.print("Removing package: ");
					out.println(toString());
				}
			}
			synchronized(packagesLock) {
				AOServDaemon.exec(
					RPM_EXE_PATH,
					"-e",
					this.toString()
				);
			}
		}
	}

	private static final Object packagesLock = new Object();
	private static DirectoryMetaSnapshot lastSnapshot;
	private static SortedSet<RPM> lastAllRpms;

	/**
	 * Gets the set of all packages installed on the server.  Because
	 * <code>rpm -aq</code> can take several seconds to run, the results are
	 * cached and only updated when a file in <code>/var/lib/rpm</code> has
	 * been modified.
	 */
	public static SortedSet<RPM> getAllRpms() throws IOException {
		synchronized(packagesLock) {
			DirectoryMetaSnapshot currentDirectorySnapshot = new DirectoryMetaSnapshot(VAR_LIB_RPM);
			if(DEBUG) {
				final PrintStream out = System.out;
				synchronized(out) {
					out.println("Got directory snapshot:");
					for(Map.Entry<String, DirectoryMetaSnapshot.FileMetaSnapshot> entry : currentDirectorySnapshot.getFiles().entrySet()) {
						out.print("    ");
						out.print(entry.getKey());
						DirectoryMetaSnapshot.FileMetaSnapshot meta = entry.getValue();
						out.print(' ');
						out.print(meta.getLastModified());
						out.print(' ');
						out.println(meta.getLength());
					}
				}
			}
			if(!currentDirectorySnapshot.equals(lastSnapshot)) {
				// Get all RPMs
				SortedSet<RPM> newAllRpms = new TreeSet<>();
				List<String> lines = StringUtility.splitLines(
					AOServDaemon.execAndCapture(
						RPM_EXE_PATH,
						"-q",
						"-a",
						"--queryformat",
						"%{NAME}\\n%{EPOCH}\\n%{VERSION}\\n%{RELEASE}\\n%{ARCH}\\n"
					)
				);
				if((lines.size()%5)!=0) throw new AssertionError("lines.size() not a multiple of 5: " + lines.size());
				for(int i=0; i<lines.size(); i+=5) {
					final String epoch = lines.get(i+1);
					final String arch = lines.get(i+4);
					newAllRpms.add(
						new RPM(
							lines.get(i),
							"(none)".equals(epoch) ? null : Integer.parseInt(epoch),
							new Version(lines.get(i+2)),
							new Version(lines.get(i+3)),
							"(none)".equals(arch) ? null : Architecture.valueOf(arch)
						)
					);
				}
				// Get snapshot after RPM transaction completes because querying the RPMs updates timestamps.
				currentDirectorySnapshot = new DirectoryMetaSnapshot(VAR_LIB_RPM);
				if(DEBUG) {
					final PrintStream out = System.out;
					synchronized(out) {
						out.println("Got directory snapshot after rpm -q:");
						for(Map.Entry<String, DirectoryMetaSnapshot.FileMetaSnapshot> entry : currentDirectorySnapshot.getFiles().entrySet()) {
							out.print("    ");
							out.print(entry.getKey());
							DirectoryMetaSnapshot.FileMetaSnapshot meta = entry.getValue();
							out.print(' ');
							out.print(meta.getLastModified());
							out.print(' ');
							out.println(meta.getLength());
						}
					}
				}
				lastSnapshot = currentDirectorySnapshot;
				lastAllRpms = Collections.unmodifiableSortedSet(newAllRpms);
				if(DEBUG) {
					final PrintStream out = System.out;
					synchronized(out) {
						out.println("Got all RPMs:");
						for(RPM rpm : lastAllRpms) {
							out.print("    ");
							out.println(rpm);
						}
					}
				}
			}
			return lastAllRpms;
		}
	}

	/**
	 * Gets the highest version of an installed package or <code>null</code> if
	 * not installed.
	 */
	public static RPM getInstalledPackage(PackageName name) throws IOException {
		// Looking through all to find highest version
		RPM highestVersionFound = null;
		for(RPM rpm : getAllRpms()) {
			if(rpm.getName().equals(name.rpmName)) highestVersionFound = rpm;
		}
		return highestVersionFound;
	}

	/**
	 * Installs a package if it is not currently installed.
	 * If the package is already installed, no action is taken.
	 * The package is installed with "yum -q -y install $NAME".
	 * If multiple packages are already installed, the highest version is returned.
	 * 
	 * @return  the highest version of RPM that is installed
	 */
	public static RPM installPackage(PackageName name) throws IOException {
		synchronized(packagesLock) {
			// Check if exists by looking through all to find highest version
			RPM highestVersionFound = getInstalledPackage(name);
			if(highestVersionFound != null) return highestVersionFound;
			// Install with yum
			if(DEBUG) {
				final PrintStream out = System.out;
				synchronized(out) {
					out.print("Installing package: ");
					out.println(name.rpmName);
				}
			}
			AOServDaemon.exec(
				YUM_EXE_PATH,
				"-q",
				"-y",
				"install",
				name.rpmName
			);
			// Must exist now
			for(RPM rpm : getAllRpms()) {
				if(rpm.getName().equals(name.rpmName)) return rpm;
			}
			throw new AssertionError("Package does not exist after yum install: " + name.rpmName);
		}
	}

	/**
	 * Installs all of the packages that are not currently installed.
	 * If a package is already installed, no action is taken for that package.
	 *
	 * @see  #installPackage(com.aoindustries.aoserv.daemon.unix.linux.PackageManager.PackageName) 
	 */
	public static void installPackages(Iterable<PackageName> packageNames) throws IOException {
		synchronized(packagesLock) {
			for(PackageName packageName : packageNames) installPackage(packageName);
		}
	}

	/**
	 * Removes the package with the provided name.  There must be only one
	 * match for the provided name.  If the RPM is not installed, no action is
	 * taken.  If more than one version is installed, an exception is thrown.
	 * 
	 * @see RPM#remove() Call remove on a specific RPM when multiple version may be installed
	 */
	public static void removePackage(PackageName name) throws IOException {
		synchronized(packagesLock) {
			List<RPM> matches = new ArrayList<>();
			for(RPM rpm : getAllRpms()) {
				if(rpm.getName().equals(name.rpmName)) matches.add(rpm);
			}
			if(!matches.isEmpty()) {
				if(matches.size() > 1) throw new IOException("More than one installed RPM matches, refusing to remove: " + name);
				matches.get(0).remove();
			}
		}
	}
}
