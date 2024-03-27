package hudson.remoting;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.security.InvalidParameterException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wrapper class for {@link System#in} and {@link System#out} on z/OS
 * 
 * This wrapper class provides means to neutralize the EBCDIC/ISO-8859-1
 * conversion by z/OS OpenSSH.
 * 
 * On z/OS platform ssh stdin and stdout receive character set conversion
 * between the EBCDIC code used at z/OS side and ISO-8859-1 character set (cf.
 * <a href=
 * "https://www.ibm.com/docs/en/zos/3.1.0?topic=systems-openssh-globalization">
 * OpenSSH and globalization</a> table 1, scenario 5). This hampers
 * communication between Jenkins ssh agents running on z/OS that communicate via
 * stdin/stdout with Jenkins controller.
 * 
 * By default the wrapper is enabled on z/OS platform and uses system property
 * {@code ibm.system.encoding} to determine the EBCDIC character set to be used.
 * 
 * The EBCDIC character set can be specified explicitly by setting system
 * property {@code hudson.remoting.ZosSshInOutStreamWrapper} to the character
 * set name.
 * 
 * To disable the wrapper set system property
 * {@code hudson.remoting.ZosSshInOutStreamWrapper} to value "disabled".
 * 
 * @author Lutz Neugebauer
 */
final public class ZosSshInOutStreamWrapper {
	private static final Logger LOGGER = Logger.getLogger(ZosSshInOutStreamWrapper.class.getName());

	/**
	 * Name of the system property to enable the wrapper and optionally set the
	 * EBCDIC encoding to be used.
	 */
	final static String SYSPROPNAME = "hudson.remoting.ZosSshInOutStreamWrapper";

	/**
	 * Singleton wrapper object
	 */
	final static ZosSshInOutStreamWrapper wrapper = new ZosSshInOutStreamWrapper();

	/**
	 * Value of the system property used for enabling/configuring the wrapper.
	 */
	final String configSyspropValue = System.getProperty(ZosSshInOutStreamWrapper.SYSPROPNAME);

	/**
	 * Flag showing whether wrapper should be active or not.
	 */
	final boolean isWrappingEnabled = System.getProperty("os.name").equals("z/OS")
			&& !"disabled".equals(configSyspropValue);

	private boolean isInitialized = false;

	/**
	 * Translation table from EBCDIC char set to ISO-8859-1
	 */
	private byte[] ebcdicToIso88591;

	/**
	 * Translation table from ISO-8859-1 char set to EBCDIC
	 */
	private byte[] iso88591ToEbcdic;

	/**
	 * Deferred initialization called when using the wrapper.
	 * 
	 * @throws CharacterCodingException
	 */
	private synchronized void initialize() throws CharacterCodingException {
		if (!isInitialized && isWrappingEnabled) {
			LOGGER.log(Level.FINE, "{0}={1}", new Object[] { SYSPROPNAME, configSyspropValue });

			// determine EBCDIC character set
			Charset zosEncoding = Charset.forName(
					configSyspropValue != null ? configSyspropValue : System.getProperty("ibm.system.encoding"));
			LOGGER.log(Level.INFO, "ssh agent stdin/out wrapping enabled with encoding {0}", zosEncoding.name());
			Charset iso88591 = Charset.forName("ISO-8859-1");

			// initialize translation tables
			byte[] identity = new byte[256];
			for (int i = 0; i <= 255; i++) {
				identity[i] = (byte) (i & 0xFF);
			}
			this.ebcdicToIso88591 = iso88591.newEncoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.encode(zosEncoding.newDecoder()
							.onMalformedInput(CodingErrorAction.REPORT)
							.onUnmappableCharacter(CodingErrorAction.REPORT)
							.decode(ByteBuffer.wrap(identity)))
					.array();
			this.iso88591ToEbcdic = zosEncoding.newEncoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.encode(iso88591.newDecoder()
							.onMalformedInput(CodingErrorAction.REPORT)
							.onUnmappableCharacter(CodingErrorAction.REPORT)
							.decode(ByteBuffer.wrap(identity)))
					.array();

			// ensure bijective mapping of selected EBCDIC char set with ISO-8859-1
			for (int i = 0; i <= 255; i++) {
				if (i != (ebcdicToIso88591[iso88591ToEbcdic[i] & 0xFF] & 0xFF)) {
					throw new InvalidParameterException("configured EBCDIC character set " + zosEncoding.name()
							+ " doesn't have required bijective mapping with ISO-8859-1");
				}
			}

			isInitialized = true;
		}
	}

	/**
	 * Wrapping InputStream
	 * 
	 * If wrapping is enabled characters read are converted from configured EBCDIC
	 * character set to ISO-8859-1 neutralizing z/OS OpenSSH's opposite conversion.
	 * 
	 * @param in the stream to be wrapped
	 * @return wrapped stream if wrapping enabled, unwrapped in otherwise
	 * 
	 * @throws IOException
	 */
	InputStream in(InputStream in) throws IOException {
		initialize();
		return isWrappingEnabled ? new FilterInputStream(in) {
			@Override
			public int read() throws IOException {
				int c = in.read();
				return c == -1 ? c : (ebcdicToIso88591[c] & 0xFF);
			}

			@Override
			public int read(byte[] b) throws IOException {
				return read(b, 0, b.length);
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int l = in.read(b, off, len);
				for (int i = off; i < (off + l); i++) {
					b[i] = ebcdicToIso88591[b[i] & 0xFF];
				}
				return l;
			}
		} : in;
	}

	/**
	 * Wrapping OutputStream
	 *
	 * If wrapping is enabled characters written are converted from ISO-8859-1 to
	 * configured EBCDIC character set neutralizing z/OS OpenSSH's opposite
	 * conversion.
	 * 
	 * @param out the stream to be wrapped
	 * @return wrapped stream if wrapping enabled, unwrapped out otherwise
	 * @throws IOException
	 */
	OutputStream out(OutputStream out) throws IOException {
		initialize();
		return isWrappingEnabled ? new FilterOutputStream(System.out) {
			@Override
			public void write(int b) throws IOException {
				out.write(iso88591ToEbcdic[b & 0xFF]);
			}

			@Override
			public void write(byte[] b) throws IOException {
				write(b, 0, b.length);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				for (int i = off; i < (off + len); i++) {
					write((int) (b[i] & 0xFF));
				}
			}
		} : out;
	}
}
