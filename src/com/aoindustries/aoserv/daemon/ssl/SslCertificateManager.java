/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.ssl;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.AlertLevel;
import static com.aoindustries.aoserv.client.AlertLevel.CRITICAL;
import static com.aoindustries.aoserv.client.AlertLevel.HIGH;
import static com.aoindustries.aoserv.client.AlertLevel.LOW;
import static com.aoindustries.aoserv.client.AlertLevel.MEDIUM;
import static com.aoindustries.aoserv.client.AlertLevel.NONE;
import com.aoindustries.aoserv.client.CyrusImapdBind;
import com.aoindustries.aoserv.client.CyrusImapdServer;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.SendmailServer;
import com.aoindustries.aoserv.client.SslCertificate;
import com.aoindustries.aoserv.client.SslCertificate.Check;
import com.aoindustries.aoserv.client.SslCertificateName;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.security.SmallIdentifier;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.Tuple2;
import com.aoindustries.util.concurrent.ConcurrencyLimiter;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.apache.commons.lang3.StringUtils;

final public class SslCertificateManager {

	private static UnixFile getUnixFile(UnixPath path) {
		return (path == null) ? null : new UnixFile(path.toString());
	}

	private static final String ALGORITHM = "SHA-256";

	/**
	 * @implNote  This is 5 minutes less than "NONE_SLEEP_DELAY" in noc-monitor/SslCertificateNodeWorker.java
	 */
	private static final long CERTBOT_CACHE_DURATION = 55L * 60 * 1000; // 55 minutes

	private static final int CERTBOT_CRITICAL_DAYS = 0;
	private static final int CERTBOT_HIGH_DAYS = 7;
	private static final int CERTBOT_MEDIUM_DAYS = 10;
	private static final int CERTBOT_LOW_DAYS = 12;

	private static final int OTHER_CRITICAL_DAYS = 0;
	private static final int OTHER_HIGH_DAYS = 7;
	private static final int OTHER_MEDIUM_DAYS = 14;
	private static final int OTHER_LOW_DAYS = 30;

	/**
	 * Wait on certbot lock instead of getting an error.
	 * There is still a potential race condition as we are not locking.  This
	 * reduces, but does not eliminate, the number of times we can a failure
	 * about another instance of certbot running.
	 * <p>
	 * Because idempotent AOServ Client commands are retried, and with the
	 * monitoring having built-in tolerance via incremental alert levels, this
	 * should be sufficient to keep this issue off the radar.
	 * </p>
	 */
	private static final UnixFile CERTBOT_LOCK = new UnixFile("/var/lib/letsencrypt/.certbot.lock");
	private static final long CERTBOT_LOCKED_SLEEP = 6000;
	private static final int CERTBOT_LOCKED_ATTEMPTS = 10;

	private static final Map<Tuple2<UnixFile,String>,Tuple2<Long,String>> getHashedCache = new HashMap<>();

	/**
	 * Gets the SHA-256 hashed output from a command, caching results when the file has not changed modified times.
	 *
	 * @implNote  This synchronizes on {@link #getHashedCache} which will serialize all commands.  This is OK as results will be cached normally.
	 */
	private static String getCommandHash(UnixFile file, String type, long modifiedTime, String ... command) throws IOException {
		try {
			Tuple2<UnixFile,String> cacheKey = new Tuple2<>(file, type);
			synchronized(getHashedCache) {
				Tuple2<Long,String> cached = getHashedCache.get(cacheKey);
				if(cached != null && cached.getElement1() == modifiedTime) return cached.getElement2();
				String hashed = StringUtility.convertToHex(
					MessageDigest.getInstance(ALGORITHM).digest(
						AOServDaemon.execAndCaptureBytes(command)
					)
				);
				getHashedCache.put(cacheKey, new Tuple2<>(modifiedTime, hashed));
				return hashed;
			}
		} catch(NoSuchAlgorithmException e) {
			throw new AssertionError(ALGORITHM + " is expected to exist", e);
		}
	}

	private static class X509Status {
		private final long certModifyTime;

		private final Date notBefore;
		private final Date notAfter;
		private final String commonName;
		private final Set<String> altNames;

		private X509Status(
			long certModifyTime,
			Date notBefore,
			Date notAfter,
			String commonName,
			Set<String> altNames
		) {
			this.certModifyTime = certModifyTime;
			this.notBefore = notBefore;
			this.notAfter = notAfter;
			this.commonName = commonName;
			this.altNames = altNames;
		}

		private Date getNotBefore() {
			return notBefore;
		}

		private Date getNotAfter() {
			return notAfter;
		}

		private String getCommonName() {
			return commonName;
		}

		private Set<String> getAltNames() {
			return altNames;
		}
	}

	private static final String FACTORY_TYPE = "X.509";

	private static final Map<UnixFile,X509Status> x509Cache = new HashMap<>();
	/**
	 * Gets the x509 status.
	 */
	private static X509Status getX509Status(UnixFile certCanonical, UnixFile keyCanonical) throws IOException {
		synchronized(x509Cache) {
			long certModifyTime = certCanonical.getStat().getModifyTime();

			X509Status cached = x509Cache.get(certCanonical);
			if(
				cached != null
				&& certModifyTime == cached.certModifyTime
			) {
				return cached;
			}

			CertificateFactory factory;
			try {
				factory = CertificateFactory.getInstance(FACTORY_TYPE);
			} catch(CertificateException e) {
				throw new IOException(FACTORY_TYPE + ": Unable to load certificate factory: " + e.toString(), e);
			}
			X509Certificate certificate;
			if(certCanonical.equals(keyCanonical)) {
				// Use openssl to convert .pem file to pkcs12 on-the-fly

				// Generate a one-time random password (64-bit random, one-shot)
				String passphrase = new SmallIdentifier().toString();
				// TODO: Try without password
				// Convert to PKCS12
				byte[] pkcs12 = AOServDaemon.execAndCaptureBytes(
					"openssl", "pkcs12", "-export", "-in", certCanonical.getPath(), "-passout", "pass:" + passphrase
				);

				// Key and cert together, load through Keystore
				// See https://stackoverflow.com/questions/21794117/java-security-cert-certificateparsingexception-signed-fields-invalid
				final String KEYSTORE_TYPE = "PKCS12";
				KeyStore ks;
				try {
					ks = KeyStore.getInstance(KEYSTORE_TYPE);
				} catch(KeyStoreException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to get keystore instance of type \"" + KEYSTORE_TYPE + "\": " + e.toString(), e);
				}
				try (InputStream in = new ByteArrayInputStream(pkcs12)) {
					ks.load(in, passphrase.toCharArray()); // TODO: Try without passphrase
				} catch(NoSuchAlgorithmException | CertificateException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to load keystore from \"" + certCanonical + "\": " + e.toString(), e);
				}
				Enumeration<String> aliases;
				try {
					aliases = ks.aliases();
				} catch(KeyStoreException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to get keystore aliases from \"" + certCanonical + "\": " + e.toString(), e);
				}
				Set<String> aliasSet = new LinkedHashSet<>();
				while(aliases.hasMoreElements()) {
					String alias = aliases.nextElement();
					if(!aliasSet.add(alias)) throw new IOException(FACTORY_TYPE + ": Duplicate alias from pkcs12 conversion from \"" + certCanonical + "\": " + alias);
				}
				if(aliasSet.isEmpty()) {
					throw new IOException(FACTORY_TYPE + ": No aliases from pkcs12 conversion from \"" + certCanonical + "\"");
				} else if(aliasSet.size() > 1) {
					throw new IOException(FACTORY_TYPE + ": More than one alias from pkcs12 conversion from \"" + certCanonical + "\": " + StringUtils.join(aliasSet, ", "));
				}
				String alias = aliasSet.iterator().next();
				try {
					certificate = (X509Certificate)ks.getCertificate(alias);
				} catch(KeyStoreException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to get certificate from pkcs12 conversion from \"" + certCanonical + "\": " + e.toString(), e);
				}
			} else {
				// Cert alone, load directly
				try (InputStream in = new FileInputStream(certCanonical.getFile())) {
					certificate = (X509Certificate)factory.generateCertificate(in);
				} catch(CertificateException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to generate certificate from \"" + certCanonical + "\": " + e.toString(), e);
				}
			}
			String commonName = null;
			{
				// See https://stackoverflow.com/questions/7933468/parsing-the-cn-out-of-a-certificate-dn
				String x509Name = certificate.getSubjectX500Principal().getName();
				LdapName ln;
				try {
					ln = new LdapName(x509Name); // Compatible: both getName() and new LdapName(...) are RFC 2253
				} catch(InvalidNameException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to parse common name \"" + x509Name + "\": " + e.toString(), e);
				}
				for(Rdn rdn : ln.getRdns()) {
					if(rdn.getType().equalsIgnoreCase("CN")) {
						commonName = rdn.getValue().toString();
						break;
					}
				}
				if(commonName == null) throw new IOException(FACTORY_TYPE + ": No common name found: " + x509Name);
			}
			Set<String> altNames;
			{
				Collection<List<?>> sans;
				try {
					sans = certificate.getSubjectAlternativeNames();
				} catch(CertificateParsingException e) {
					throw new IOException(FACTORY_TYPE + ": Unable to parse certificate subject alt names: " + e.toString(), e);
				}
				if(sans == null) {
					altNames = Collections.emptySet();
				} else {
					altNames = new LinkedHashSet<>(sans.size() *4/3+1);
					for(List<?> san : sans) {
						int type = (Integer)san.get(0);
						if(type == 2 /* dNSName */) {
							String altName = (String)san.get(1);
							if(!altNames.add(altName)) throw new IOException(FACTORY_TYPE + ": Duplicate subject alt name: " + altName);
						} else {
							throw new IOException(FACTORY_TYPE + ": Unexpected subject alt name type code: " + type);
						}
					}
				}
			}
			X509Status result = new X509Status(
				certModifyTime,
				certificate.getNotBefore(),
				certificate.getNotAfter(),
				commonName,
				altNames
			);
			x509Cache.put(certCanonical, result);
			return result;
		}
	}

	private static class CertbotStatus {
		private final long cacheTime;
		private final UnixFile certCanonicalFile;
		private final long certModifyTime;
		private final UnixFile chainCanonicalFile;
		private final long chainModifyTime;
		private final UnixFile fullchainCanonicalFile;
		private final long fullchainModifyTime;
		private final UnixFile privkeyCanonicalFile;
		private final long privkeyModifyTime;
		private final long renewalModifyTime;

		private final Set<String> domains;
		private final String status;
		private final int days;

		private CertbotStatus(
			long cacheTime,
			UnixFile certCanonicalFile,
			long certModifyTime,
			UnixFile chainCanonicalFile,
			long chainModifyTime,
			UnixFile fullchainCanonicalFile,
			long fullchainModifyTime,
			UnixFile privkeyCanonicalFile,
			long privkeyModifyTime,
			long renewalModifyTime,
			Set<String> domains,
			String status,
			int days
		) {
			this.cacheTime = cacheTime;
			this.certCanonicalFile = certCanonicalFile;
			this.certModifyTime = certModifyTime;
			this.chainCanonicalFile = chainCanonicalFile;
			this.chainModifyTime = chainModifyTime;
			this.fullchainCanonicalFile = fullchainCanonicalFile;
			this.fullchainModifyTime = fullchainModifyTime;
			this.privkeyCanonicalFile = privkeyCanonicalFile;
			this.privkeyModifyTime = privkeyModifyTime;
			this.renewalModifyTime = renewalModifyTime;
			this.domains = domains;
			this.status = status;
			this.days = days;
		}

		private Set<String> getDomains() {
			return domains;
		}

		private String getStatus() {
			return status;
		}

		/**
		 * @return  the number of days valid or {@code -1} if invalid
		 */
		private int getDays() {
			return days;
		}
	}

	private static final Map<String,CertbotStatus> certbotCache = new HashMap<>();
	/**
	 * Gets the certificate status from certbot.
	 */
	private static CertbotStatus getCertbotStatus(String certbotName) throws IOException {
		synchronized(certbotCache) {
			long currentTime = System.currentTimeMillis();
			UnixFile certCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/cert.pem").getCanonicalFile());
			long certModifyTime = certCanonicalFile.getStat().getModifyTime();
			UnixFile chainCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/chain.pem").getCanonicalFile());
			long chainModifyTime = chainCanonicalFile.getStat().getModifyTime();
			UnixFile fullchainCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/fullchain.pem").getCanonicalFile());
			long fullchainModifyTime = fullchainCanonicalFile.getStat().getModifyTime();
			UnixFile privkeyCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/privkey.pem").getCanonicalFile());
			long privkeyModifyTime = privkeyCanonicalFile.getStat().getModifyTime();
			UnixFile renewalFile = new UnixFile("/etc/letsencrypt/renewal/" + certbotName + ".conf");
			long renewalModifyTime = renewalFile.getStat().getModifyTime();
			CertbotStatus cached = certbotCache.get(certbotName);
			if(
				cached != null
				&& (currentTime - cached.cacheTime) < CERTBOT_CACHE_DURATION
				&& (cached.cacheTime - currentTime) < CERTBOT_CACHE_DURATION
				&& certCanonicalFile.equals(cached.certCanonicalFile)
				&& certModifyTime == cached.certModifyTime
				&& chainCanonicalFile.equals(cached.chainCanonicalFile)
				&& chainModifyTime == cached.chainModifyTime
				&& fullchainCanonicalFile.equals(cached.fullchainCanonicalFile)
				&& fullchainModifyTime == cached.fullchainModifyTime
				&& privkeyCanonicalFile.equals(cached.privkeyCanonicalFile)
				&& privkeyModifyTime == cached.privkeyModifyTime
				&& renewalModifyTime == cached.renewalModifyTime
			) {
				return cached;
			}
			Set<String> domains = Collections.emptySet();
			String status = "UNKNOWN";
			int days = -1;
			// Wait until lock file removed
			{
				int failures = 0;
				while(CERTBOT_LOCK.getStat().exists()) {
					failures++;
					if(failures >= CERTBOT_LOCKED_ATTEMPTS) {
						throw new IOException("certbot locked by " + CERTBOT_LOCK);
					}
					try {
						Thread.sleep(CERTBOT_LOCKED_SLEEP);
					} catch(InterruptedException e) {
						InterruptedIOException ioErr = new InterruptedIOException("Interrupted waiting on " + CERTBOT_LOCK);
						ioErr.initCause(e);
						throw ioErr;
					}
				}
			}
			try (
				BufferedReader in = new BufferedReader(
					new StringReader(
						AOServDaemon.execAndCapture("certbot", "certificates", "--cert-name", certbotName)
					)
				)
			) {
				String line;
				while((line = in.readLine()) != null) {
					if(line.startsWith("  Certificate Name: ")) {
						if(!certbotName.equals(line.substring("  Certificate Name: ".length()))) {
							throw new IOException("Unexpected certificate name: " + line);
						}
					}
					String domainsPrefix = "    Domains: ";
					if(line.startsWith(domainsPrefix)) {
						if(!domains.isEmpty()) throw new IOException("Domains already set: " + line);
						String[] split = StringUtils.split(line.substring(domainsPrefix.length()), ' ');
						if(split.length == 0) throw new IOException("No domains: " + line);
						domains = new LinkedHashSet<>(split.length*4/3+1);
						for(String domain : split) {
							if(!domains.add(domain)) throw new IOException("Duplicate domain from certbot: " + line);
						}
					}
					if(line.startsWith("    Expiry Date: ")) {
						int leftPos = line.indexOf('(');
						if(leftPos != -1) {
							int rightPos = line.indexOf(')', leftPos + 1);
							if(rightPos != -1) {
								String substr = line.substring(leftPos + 1, rightPos).trim();
								int colonPos = substr.indexOf(':');
								if(colonPos == -1) {
									status = substr.trim();
									days = -1;
								} else {
									try {
										String daysString = substr.substring(colonPos + 1).trim();
										if(daysString.endsWith(" days")) {
											daysString = daysString.substring(0, daysString.length() - " days".length()).trim();
										} else if(daysString.endsWith(" day")) {
											daysString = daysString.substring(0, daysString.length() - " day".length()).trim();
										}
										days = Integer.parseInt(daysString);
										if(days < -1) days = -1;
										status = substr.substring(0, colonPos).trim();
									} catch(NumberFormatException e) {
										status = substr;
										days = -1;
									}
								}
							}
						}
					}
				}
			}
			CertbotStatus result = new CertbotStatus(
				currentTime,
				certCanonicalFile,
				certModifyTime,
				chainCanonicalFile,
				chainModifyTime,
				fullchainCanonicalFile,
				fullchainModifyTime,
				privkeyCanonicalFile,
				privkeyModifyTime,
				renewalModifyTime,
				domains,
				status,
				days
			);
			certbotCache.put(certbotName, result);
			return result;
		}
	}

	private static final ConcurrencyLimiter<SslCertificate,List<Check>> checkSslCertificateConcurrencyLimiter = new ConcurrencyLimiter<>();

	public static List<Check> checkSslCertificate(SslCertificate certificate) throws IOException, SQLException {
		try {
			AOServer thisAOServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			boolean isNewOpenssl;
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_DOM0_I686 :
				case OperatingSystemVersion.CENTOS_5_DOM0_X86_64 :
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
					isNewOpenssl = false;
					break;
				case OperatingSystemVersion.CENTOS_7_DOM0_X86_64 :
				case OperatingSystemVersion.CENTOS_7_X86_64 :
					isNewOpenssl = true;
					break;
				default :
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
			return checkSslCertificateConcurrencyLimiter.executeSerialized(
				certificate,
				() -> {
					long currentTime = System.currentTimeMillis();

					String certbotName = certificate.getCertbotName();
					String commonName = certificate.getCommonName().getName();
					// Gets a lowercase set of alts names
					Set<String> expectedAlts;
					Set<String> expectedAltsLower;
					{
						List<SslCertificateName> altNames = certificate.getAltNames();
						expectedAlts = new LinkedHashSet<>(altNames.size()*4/3+1);
						expectedAltsLower = new LinkedHashSet<>(altNames.size()*4/3+1);
						for(SslCertificateName altName : altNames) {
							String name = altName.getName();
							if(!expectedAlts.add(name)) throw new SQLException("Duplicate alt name: " + name);
							String lower = name.toLowerCase(Locale.ROOT);
							if(!expectedAltsLower.add(lower)) throw new SQLException("Duplicate lower alt name: " + lower);
						}
					}
					List<Check> results = new ArrayList<>();

					// First make sure all expected files exist
					UnixFile keyFile   = getUnixFile(certificate.getKeyFile());
					UnixFile csrFile   = getUnixFile(certificate.getCsrFile());
					UnixFile certFile  = getUnixFile(certificate.getCertFile());
					UnixFile chainFile = getUnixFile(certificate.getChainFile());

					// Stat each file
					Stat keyStat   = keyFile.getStat();
					Stat csrStat   = (csrFile == null) ? null : csrFile.getStat();
					Stat certStat  = certFile.getStat();
					Stat chainStat = (chainFile == null) ? null : chainFile.getStat();

					// Make sure each expected file exists
					boolean keyExists = keyStat.exists();
					results.add(new Check("Key exists?", Boolean.toString(keyExists), keyExists ? NONE : CRITICAL, keyFile.toString()));
					boolean csrExists;
					if(csrStat != null) {
						assert csrFile != null;
						csrExists = csrStat.exists();
						results.add(new Check("CSR exists?", Boolean.toString(csrExists), csrExists ? NONE : MEDIUM, csrFile.toString()));
					} else {
						csrExists = false;
					}
					boolean certExists = certStat.exists();
					results.add(new Check("Cert exists?", Boolean.toString(certExists), certExists ? NONE : CRITICAL, certFile.toString()));
					boolean chainExists;
					if(chainStat != null) {
						assert chainFile != null;
						chainExists = chainStat.exists();
						results.add(new Check("Chain exists?", Boolean.toString(chainExists), chainExists ? NONE : CRITICAL, chainFile.toString()));
					} else {
						chainExists = false;
					}

					// Follow symbolic links to target
					UnixFile keyCanonical;
					Stat keyCanonicalStat;
					boolean keyCanonicalExists;
					if(keyExists && keyStat.isSymLink()) {
						keyCanonical = new UnixFile(keyFile.getFile().getCanonicalPath());
						keyCanonicalStat = keyCanonical.getStat();
						keyCanonicalExists = keyCanonicalStat.exists();
						results.add(new Check("Canonical key exists?", Boolean.toString(keyCanonicalExists), keyCanonicalExists ? NONE : CRITICAL, keyCanonical.toString()));
					} else {
						keyCanonical = keyFile;
						keyCanonicalStat = keyStat;
						keyCanonicalExists = keyExists;
					}
					UnixFile csrCanonical;
					Stat csrCanonicalStat;
					boolean csrCanonicalExists;
					if(csrExists && csrStat.isSymLink()) {
						csrCanonical = new UnixFile(csrFile.getFile().getCanonicalPath());
						csrCanonicalStat = csrCanonical.getStat();
						csrCanonicalExists = csrCanonicalStat.exists();
						results.add(new Check("Canonical CSR exists?", Boolean.toString(csrCanonicalExists), csrCanonicalExists ? NONE : MEDIUM, csrCanonical.toString()));
					} else {
						csrCanonical = csrFile;
						csrCanonicalStat = csrStat;
						csrCanonicalExists = csrExists;
					}
					UnixFile certCanonical;
					Stat certCanonicalStat;
					boolean certCanonicalExists;
					if(certExists && certStat.isSymLink()) {
						certCanonical = new UnixFile(certFile.getFile().getCanonicalPath());
						certCanonicalStat = certCanonical.getStat();
						certCanonicalExists = certCanonicalStat.exists();
						results.add(new Check("Canonical cert exists?", Boolean.toString(certCanonicalExists), certCanonicalExists ? NONE : CRITICAL, certCanonical.toString()));
					} else {
						certCanonical = certFile;
						certCanonicalStat = certStat;
						certCanonicalExists = certExists;
					}
					UnixFile chainCanonical;
					Stat chainCanonicalStat;
					boolean chainCanonicalExists;
					if(chainExists && chainStat.isSymLink()) {
						chainCanonical = new UnixFile(chainFile.getFile().getCanonicalPath());
						chainCanonicalStat = chainCanonical.getStat();
						chainCanonicalExists = chainCanonicalStat.exists();
						results.add(new Check("Canonical chain exists?", Boolean.toString(chainCanonicalExists), chainCanonicalExists ? NONE : CRITICAL, chainCanonical.toString()));
					} else {
						chainCanonical = chainFile;
						chainCanonicalStat = chainStat;
						chainCanonicalExists = chainExists;
					}

					// Get the last modified of each file, or 0 for a file that doesn't exist
					long keyModifyTime   = keyCanonicalExists   ? keyCanonicalStat  .getModifyTime() : 0;
					long csrModifyTime   = csrCanonicalExists   ? csrCanonicalStat  .getModifyTime() : 0;
					long certModifyTime  = certCanonicalExists  ? certCanonicalStat .getModifyTime() : 0;
					long chainModifyTime = chainCanonicalExists ? chainCanonicalStat.getModifyTime() : 0; // TODO: How to verify chain?

					// New: Compare by public key: https://www.sslshopper.com/certificate-key-matcher.html
					// Old: Compare by modulus: https://support.asperasoft.com/hc/en-us/articles/216128468-OpenSSL-commands-to-check-and-verify-your-SSL-certificate-key-and-CSR
					String keyHash  = keyCanonicalExists  ? getCommandHash(
						keyCanonical,
						isNewOpenssl ? "pkey" : "rsa",
						keyModifyTime,
						isNewOpenssl
							? new String[] {"openssl", "pkey", "-outform", "PEM", "-in", keyCanonical.getPath(), "-pubout"}
							: new String[] {"openssl", "rsa", "-in", keyCanonical.getPath(), "-noout", "-modulus"}
					) : null;
					String csrHash  = csrCanonicalExists  ? getCommandHash(
						csrCanonical,
						"req",
						csrModifyTime,
						isNewOpenssl
							? new String[] {"openssl", "req", "-outform", "PEM", "-in", csrCanonical.getPath(), "-pubkey", "-noout"}
							: new String[] {"openssl", "req", "-in", csrCanonical.getPath(), "-noout", "-modulus"}
					) : null;
					String certHash = certCanonicalExists ? getCommandHash(
						certCanonical,
						"x509",
						certModifyTime,
						isNewOpenssl
							? new String[] {"openssl", "x509", "-outform", "PEM", "-in", certCanonical.getPath(), "-pubkey", "-noout"}
							: new String[] {"openssl", "x509", "-in", certCanonical.getPath(), "-noout", "-modulus"}
					) : null;
					// TODO: Do we need to support both cert and fullchain files no SslCertificate class?  Check both with x509 command for match
					// TODO: PostgreSQL uses fullchain for Let's Encrypt.
					if(keyHash != null) {
						results.add(new Check("Key " + ALGORITHM, keyHash, NONE, null));
					}
					if(csrHash != null) {
						if(keyHash == null || keyHash.equals(csrHash)) {
							results.add(new Check("CSR " + ALGORITHM, csrHash, NONE, null));
						} else {
							results.add(new Check("CSR " + ALGORITHM, csrHash, MEDIUM, "CSR does not match Key"));
						}
					}
					if(certHash != null) {
						if(keyHash == null || keyHash.equals(certHash)) {
							results.add(new Check("Cert " + ALGORITHM, certHash, NONE, null));
						} else {
							results.add(new Check("Cert " + ALGORITHM, certHash, CRITICAL, "Cert does not match Key"));
						}
					}

					// TODO: cyrus and sendmail copies match (for let's encrypt) (mysql, postgresql later)
					// TODO: cyrus and sendmail permissions as expected (possibly on copy) (mysql, postgresql later)

					// Configuration:
					// TODO: DNS settings
					// TODO: Hostnames match what is used by (case-insensitive match), low if match but different case?
					//       HttpdSiteBinds, CyrusServers, SendmailServers, ...
					// TODO: No extra hostnames - low level warning if any found

					// TODO: Self-signed as low
					// TODO: Untrusted by openssl as high (with chain and fullchain verified separately)
					//       or Java's Certificate.verify method?

					// TODO: Certificate max alert level setting? (with an "until" date?) - this used to cap in NOC or restrict statuses here?

					// x509 parsing
					if(certCanonicalExists) {
						DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.LONG);
						df.setTimeZone(thisAOServer.getTimeZone().getTimeZone());

						X509Status x509Status = getX509Status(certCanonical, keyCanonical);
						Date notBefore = x509Status.getNotBefore();
						if(notBefore != null) {
							results.add(
								new Check(
									FACTORY_TYPE + " Not Before",
									df.format(notBefore),
									currentTime < notBefore.getTime() ? CRITICAL : NONE,
									null
								)
							);
						}
						Date notAfter = x509Status.getNotAfter();
						if(notAfter != null) {
							long daysLeft = (notAfter.getTime() - currentTime) / (24 * 60 * 60 * 1000);
							String dateStr = df.format(notAfter);
							AlertLevel alertLevel = (daysLeft <= (certbotName != null ? CERTBOT_CRITICAL_DAYS : OTHER_CRITICAL_DAYS)) ? CRITICAL
										: daysLeft <= (certbotName != null ? CERTBOT_HIGH_DAYS : OTHER_HIGH_DAYS) ? HIGH
										: daysLeft <= (certbotName != null ? CERTBOT_MEDIUM_DAYS : OTHER_MEDIUM_DAYS) ? MEDIUM
										: daysLeft <= (certbotName != null ? CERTBOT_LOW_DAYS : OTHER_LOW_DAYS) ? LOW
										: NONE;
							results.add(
								new Check(
									FACTORY_TYPE + " Not After",
									dateStr,
									alertLevel,
									alertLevel == NONE
										? null
										: (
											(alertLevel == CRITICAL ? "Certificate expired " : "Certificate expires ")
											+ dateStr
										)
								)
							);
						}
						String x509CN = x509Status.getCommonName();
						boolean commonNameMatches = x509CN.equals(commonName);
						boolean commonNameMatchesIgnoreCase = x509CN.equalsIgnoreCase(commonName);
						results.add(
							new Check(
								FACTORY_TYPE + " Subject CN",
								x509CN,
								commonNameMatches ? NONE
									: commonNameMatchesIgnoreCase ? LOW : HIGH,
								commonNameMatches ? null : "Expected: " + commonName
							)
						);
						// The set of alt names should match expected
						Set<String> altNames = x509Status.getAltNames();
						boolean altNamesMatch = expectedAlts.equals(altNames);
						boolean altNamesMatchLower;
						if(altNamesMatch) {
							altNamesMatchLower = true;
						} else {
							Set<String> lowerAltnames = new LinkedHashSet<>(altNames.size() *4/3+1);
							for(String altName : altNames) {
								String lower = altName.toLowerCase(Locale.ROOT);
								if(!lowerAltnames.add(lower)) throw new IOException("Duplicate lower alt name: " + lower);
							}
							altNamesMatchLower = expectedAltsLower.equals(lowerAltnames);
						}
						results.add(
							new Check(
								FACTORY_TYPE + " Subject Alternative Name",
								StringUtils.join(altNames, ' '),
								altNamesMatch ? NONE : altNamesMatchLower ? LOW : HIGH,
								altNamesMatch ? null : "Expected: " + StringUtils.join(expectedAlts, ' ')
							)
						);
					}

					// Let's Encrypt certificate status
					if(certbotName != null) {
						CertbotStatus certbotStatus = getCertbotStatus(certbotName);

						String status = certbotStatus.getStatus();
						results.add(new Check("Certbot status", status, "VALID".equals(status) ? NONE : CRITICAL, null));
						int days = certbotStatus.getDays();
						results.add(
							new Check(
								"Certbot days left",
								days == -1 ? "EXPIRED" : Integer.toString(days),
								days == -1 || days <= CERTBOT_CRITICAL_DAYS ? CRITICAL
									: days <= CERTBOT_HIGH_DAYS ? HIGH
									: days <= CERTBOT_MEDIUM_DAYS ? MEDIUM
									: days <= CERTBOT_LOW_DAYS ? LOW
									: NONE,
								null
							)
						);

						final String CERTBOT_DOMAINS = "Certbot Subject Alternative Name";
						Set<String> domains = certbotStatus.getDomains();
						if(domains.isEmpty()) {
							results.add(
								new Check(
									CERTBOT_DOMAINS,
									"(empty)",
									HIGH,
									"No domains from certbot"
								)
							);
						} else {
							// The first domain should equal the common name
							String firstDomain = domains.iterator().next();
							boolean commonNameMatches = firstDomain.equals(commonName);
							boolean commonNameMatchesIgnoreCase = firstDomain.equalsIgnoreCase(commonName);
							results.add(
								new Check(
									"Certbot Subject CN",
									firstDomain,
									commonNameMatches ? NONE
										: commonNameMatchesIgnoreCase ? LOW : HIGH,
									commonNameMatches ? null : "Expected: " + commonName
								)
							);

							// The set of domains should match Alt names
							boolean altNamesMatch = expectedAlts.equals(domains);
							boolean altNamesMatchLower;
							if(altNamesMatch) {
								altNamesMatchLower = true;
							} else {
								Set<String> lowerDomains = new LinkedHashSet<>(domains.size() *4/3+1);
								for(String domain : domains) {
									String lower = domain.toLowerCase(Locale.ROOT);
									if(!lowerDomains.add(lower)) throw new IOException("Duplicate lower domain: " + lower);
								}
								altNamesMatchLower = expectedAltsLower.equals(lowerDomains);
							}
							results.add(
								new Check(
									CERTBOT_DOMAINS,
									StringUtils.join(domains, ' '),
									altNamesMatch ? NONE : altNamesMatchLower ? LOW : HIGH,
									altNamesMatch ? null : "Expected: " + StringUtils.join(expectedAlts, ' ')
								)
							);
						}
					}

					// Low-level if certificate appears unused
					List<CyrusImapdBind> cyrusBinds = certificate.getCyrusImapdBinds();
					List<CyrusImapdServer> cyrusServers = certificate.getCyrusImapdServers();
					List<HttpdSiteBind> hsbs = certificate.getHttpdSiteBinds();
					List<SendmailServer> sendmailServers = certificate.getSendmailServersByServerCertificate();
					List<SendmailServer> sendmailClients = certificate.getSendmailServersByClientCertificate();
					int useCount = 0;
					StringBuilder usedBy = new StringBuilder();
					if(!cyrusBinds.isEmpty()) {
						int size = cyrusBinds.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " CyrusImapdBind" : " CyrusImapdBinds");
					}
					if(!cyrusServers.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = cyrusServers.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " CyrusImapdServer" : " CyrusImapdServer");
					}
					if(!hsbs.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = hsbs.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " HttpdSiteBind" : " HttpdSiteBind");
					}
					if(!sendmailServers.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = sendmailServers.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " SendmailServer(Server)" : " SendmailServers(Server)");
					}
					if(!sendmailClients.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = sendmailClients.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " SendmailServer(Client)" : " SendmailServers(Client)");
					}
					results.add(
						new Check(
							"Certificate used?",
							Integer.toString(useCount),
							useCount == 0 ? LOW : NONE,
							usedBy.length() == 0 ? "Certificate appears to be unused" : usedBy.toString()
						)
					);

					return results;
				}
			);
		} catch(InterruptedException e) {
			InterruptedIOException ioErr = new InterruptedIOException();
			ioErr.initCause(e);
			throw ioErr;
		} catch(ExecutionException e) {
			Throwable cause = e.getCause();
			if(cause instanceof IOException) throw (IOException)cause;
			if(cause instanceof SQLException) throw (SQLException)cause;
			throw new IOException(cause);
		}
	}

	private SslCertificateManager() {}
}
