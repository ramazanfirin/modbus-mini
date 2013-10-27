package ua.com.certa.modbus;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModbusRtuOverTcpClient extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusRtuOverTcpClient.class);

	private final String remoteAddressString;
	private final int remotePort; 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private final int timeout;
	private final int pause;
	private final boolean keepConnection; 

	private Socket socket;
	private int expectedBytes; // for logging

	private final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus RTU ADU: [ID(1), PDU(n), CRC(2)]

	public ModbusRtuOverTcpClient(String remoteHost, int remotePort, String localIP, int localPort, int timeout, int pause, boolean keepConnection) {
		this.remoteAddressString = remoteHost;
		this.remotePort = remotePort;
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
		this.timeout = timeout;
		this.keepConnection = keepConnection;
		this.pause = pause;
	}

	private void openSocket() throws IOException {
		if (socket != null)
			return;
		log.info("Opening socket: {}:{} <-> {}:{}", localAddressString, localPort, remoteAddressString, remotePort);
		InetAddress remoteAddress = InetAddress.getByName(remoteAddressString);
		InetAddress localAddress = InetAddress.getByName(localAddressString);
		socket = new Socket(remoteAddress, remotePort, localAddress, localPort);
		socket.setSoTimeout(timeout);
		log.info("Socket opened: {} <-> {}", socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());
	}

	public void clearInput() throws IOException {
		InputStream in = socket.getInputStream();
		int avail = in.available();
		while (avail > 0) {
			int count = Math.min(avail, buffer.length);
			in.read(buffer, 0, count);
			if (log.isWarnEnabled())
				log.warn("Unexpected input: " + ModbusUtils.toHex(buffer, 0, count));
			avail = in.available();
		}
	}

	@Override
	protected Logger getLog() {
		return log;
	}
	
	@Override
	protected void sendRequest() throws IOException {
		if (pause > 0)
			try {
				Thread.sleep(pause);
			} catch (InterruptedException e) {
				// ignore
			}
		openSocket();
		clearInput();
		buffer[0] = getServerId();
		System.arraycopy(pdu, 0, buffer, 1, pduSize);
		int size = pduSize + 1;
		int crc = ModbusUtils.calcCRC16(buffer, 0, size);
		buffer[size] = lowByte(crc);
		buffer[size + 1] = highByte(crc);
		size = size + 2;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusUtils.toHex(buffer, 0, size));
		socket.getOutputStream().write(buffer, 0, size);
	}

	private boolean readToBuffer(int start, int length) throws IOException {
		InputStream in = socket.getInputStream();
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		int offset = start;
		int bytesToRead = length;
		int res;
		while ((now < deadline) && (bytesToRead > 0)) {
			try {
				res = in.read(buffer, offset, bytesToRead);
			} catch (SocketTimeoutException e) {
				break;
			}
			if (res < 0)
				break;
			offset += res;
			bytesToRead -= res;
			if (bytesToRead > 0) // only to avoid redundant call of System.currentTimeMillis()
				now = System.currentTimeMillis();
		}
		res = length - bytesToRead; // total bytes read
		if (res < length) {
			if ((res > 0) && log.isTraceEnabled())
				log.trace("Read (incomplete): " + ModbusUtils.toHex(buffer, 0, start + res));
			log.warn("Response timeout ({} bytes, need {})", start + res, expectedBytes);
			return false;
		}
		else
			return true;
	}

	private boolean crcValid(int size) {
		int crc = ModbusUtils.calcCRC16(buffer, 0, size);
		int crc2 = bytesToInt16(buffer[size], buffer[size + 1]) & 0xFFFF; 
		if (crc == crc2)
			return true;
		else {
			if (log.isWarnEnabled())
				log.warn("CRC error (calc: {}, in response: {})", Integer.toHexString(crc), Integer.toHexString(crc2));
			return false;
		}
	}
	
	private void logData(String kind, int start, int length) {
		if (log.isTraceEnabled()) 
			log.trace("Read ({}): {}", kind, ModbusUtils.toHex(buffer, start, length));
	}
	
	// returns RESULT_*
	public int readIdToBuffer(byte expected) throws IOException {
		InputStream in = socket.getInputStream();
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		int res = 0;
		while (now < deadline) {
			try {
				res = in.read(buffer, 0, 1);
			} catch (SocketTimeoutException e) {
				//log.warn("Response timeout");
				return RESULT_TIMEOUT;
			}
			if (res >= 0) {
				if (buffer[0] == expected)
					return RESULT_OK;
				else {
					logData("bad id", 0, 1);
					//log.warn("Unexpected id: {} (need {})", buffer[0], expected);
				}
			}
		}
		return RESULT_TIMEOUT;
	}

	@Override
	protected int waitResponse() throws IOException {
		openSocket();
		try {
			expectedBytes = getExpectedPduSize() + 3; // id(1), PDU(n), crc(2)
			
			// read id
			int r = readIdToBuffer(getServerId());
			if (r != RESULT_OK)
				return r;

			// read function (bit7 means exception)
			if (!readToBuffer(1, 1))
				return RESULT_TIMEOUT;
			if ((buffer[1] & 0x7f) != getFunction()) {
				logData("bad function", 0, 2);
				log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], getFunction());
				return RESULT_BAD_RESPONSE;
			}
			
			if ((buffer[1] & 0x80) != 0) {
				// EXCEPTION
				expectedBytes = 5; // id(1), function(1), exception code(1), crc(2)
				if (!readToBuffer(2, 3)) // exception code + CRC
					return RESULT_TIMEOUT;
				if (crcValid(3)) {
					logData("exception", 0, expectedBytes);
					pduSize = 2; // function + exception code
					System.arraycopy(buffer, 1, pdu, 0, pduSize);
					return RESULT_EXCEPTION;
				}
				else {
					logData("bad crc (exception)", 0, expectedBytes);
					return RESULT_BAD_RESPONSE;
				}
			}
			else {
				// NORMAL RESPONSE
				if (!readToBuffer(2, getExpectedPduSize() + 1)) // data + CRC (without function)
					return RESULT_TIMEOUT;
				// CRC check of (serverId + PDU)
				if (crcValid(1 + getExpectedPduSize())) {
					logData("normal", 0, expectedBytes);
					pduSize = getExpectedPduSize();
					System.arraycopy(buffer, 1, pdu, 0, pduSize);
					return RESULT_OK;
				}
				else {
					logData("bad crc", 0, expectedBytes);
					return RESULT_BAD_RESPONSE;
				}
			}
		} finally {
			if (!keepConnection)
				close();
		}
	}

	@Override
	public void close() throws IOException {
		Socket t = socket;
		socket = null;
		if (t != null) {
			log.info("Closing socket");
			t.close();
			log.info("Socket closed");
		}
	}

}
