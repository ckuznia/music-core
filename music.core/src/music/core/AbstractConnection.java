package music.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractConnection {

	private static final Logger log = LogManager.getLogger(AbstractConnection.class);
	
	private final Socket socket;
	
	// Lists for input and output streams that are used (besides the basic InputStream/OutputStream).
	// Storing them in a list and closing them in a loop ensures all streams have been closed.
	private final ArrayList<InputStream> inStreams = new ArrayList<InputStream>();
	private final ArrayList<OutputStream> outStreams = new ArrayList<OutputStream>();
	
	// Establishing the maximum size for files that will be transferred
	private final long MEGA_BYTE = 1000 * 1000;
	private final long MAX_SIZE_ALLOWED = 200 * MEGA_BYTE;
	
	public AbstractConnection(Socket socket) {
		this.socket = socket;
		
		try {
			/*
			 * Input streams block until their corresponding output streams have "written and flushed the header".
			 * Therefore output streams are setup first, and then the input streams
			 */
			// Output streams setup
			log.debug("Setting up output streams...");
			OutputStream out = socket.getOutputStream();
			outStreams.add(new ObjectOutputStream(out));
			outStreams.add(new BufferedOutputStream(out));
			outStreams.add(new DataOutputStream(out));
			
			// Flushing output streams
			out.flush();
			for(OutputStream stream: outStreams) {
				stream.flush();
			}
			log.debug("Done.");
			
			// Input streams setup
			log.debug("Setting up input streams...");
			InputStream in = socket.getInputStream();
			inStreams.add(new ObjectInputStream(in));
			inStreams.add(new BufferedInputStream(in));
			inStreams.add(new DataInputStream(in));
			log.debug("Done.");
			
		} catch (IOException e) {
			log.error("Failed to set up streams with " + socket.getInetAddress(), e);
			disconnect();
			return;
		}
	}
	
	private InputStream getInput(Class<?> classType) {
		for(InputStream stream: inStreams) {
			if(stream.getClass() == classType) return stream;
		}
		log.error("Input stream " + classType + " could not be found");
		disconnect();
		return null;
	}
	
	private OutputStream getOutput(Class<?> classType) {
		for(OutputStream stream: outStreams) {
			if(stream.getClass() == classType) return stream;
		}
		log.error("Output stream " + classType + " could not be found");
		disconnect();
		return null;
	}
	
	protected int readInt() {
		try {
			return ((DataInputStream) getInput(DataInputStream.class)).readInt();
		} catch (EOFException e) {
			log.error("Read reached end of stream before finished reading from " + socket.getInetAddress(), e);
			disconnect();
		} catch(IOException e) {
			log.error("IO error when awaiting message from " + socket.getInetAddress(), e);
			disconnect();
		} catch(NullPointerException e) {
			log.error(e);
			disconnect();
		}
		return -1;
	}
	
	protected void sendInt(int value) {
		final DataOutputStream out = (DataOutputStream) getOutput(DataOutputStream.class);
		try {
			out.flush();
			
			// Send a message in the form of an integer
			log.debug("Sending integer: " + value);
			out.writeInt(value);
			out.flush();
			log.debug("Done.");
		} catch (IOException e) {
			log.error("Error sending integer [" + value + "] to [" + socket.getInetAddress() + "].", e);
			disconnect();
		}
	}
	
	protected void downloadFile(String newPath) {
		DataInputStream dInput = (DataInputStream) getInput(DataInputStream.class);
		
		
		
//		// Making sure the file isn't above an allowed limit
//		if(fileSize > MAX_SIZE_ALLOWED) {
//			log.error("File size of " + (fileSize / MEGA_BYTE) + "MB is above the allowed limit of " + MAX_SIZE_ALLOWED + "MB. The file download will be CANCELLED", new Exception("File size too large."));
//			disconnect();
//			return;
//		}
		
		// TEST
//		log.debug("Waiting for data... ");
//		try {
//			while(dInput.available() == 0) {
//				try {
//					Thread.sleep(1);
//				} catch(Exception e) {}
//			}
//		} catch(IOException e) {
//			log.error("Error waiting for data.", e);
//		}
//		log.debug("Data available.");
		
		// Retrieving file extension
		String extension = "";
		try {
			extension = dInput.readUTF();
		} catch (IOException e) {
			log.error("IO Error getting file extension.", e);
			disconnect();
			return;
		}
		
		// Getting file size
		long fileSize = -1;
		try {
			fileSize = dInput.readLong();
		} catch(IOException e) {
			log.error("IO Error getting file size from " + socket.getInetAddress(), e);
			disconnect();
			return;
		}
		
		// Creating name for file to be downloaded, name is based on current date and time
		DateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy_HH-mm-ss");
		Date date = new Date();
		String name = dateFormat.format(date) + extension;
		
		try(
			// For storing the incoming file (saving)
			FileOutputStream fOutput = new FileOutputStream(newPath + "/" + name);
			BufferedOutputStream bOutput = new BufferedOutputStream(fOutput)
		) {
			fOutput.flush();
			bOutput.flush();
						
			// For reading the incoming file
			BufferedInputStream bInput = (BufferedInputStream) getInput(BufferedInputStream.class);
			
			// Declaring buffer size
			int bufferSize = 1024 * 8;
			byte[] bytes = new byte[bufferSize];
			
			log.debug("Downloading " + (fileSize / 1000.0) + "kb file...");
			//log.info("==============================================");
			
			int bytesReceived = 0;
			// Reading from the input stream and saving to a file	
			for(int bytesRead; bytesReceived < fileSize && (bytesRead = bInput.read(bytes)) != -1;) {
				bOutput.write(bytes, 0, bytesRead);
				bytesReceived += bytesRead;
				//log.info("Got " + bytesRead + " bytes [" + bytesReceived + " of " + fileSize + " bytes received].");
			}
			bOutput.flush();
			fOutput.flush();
			
			//log.info("==============================================");
			log.debug("Done.");
		} catch (EOFException e) {
			log.error("End of file error.", e);
			disconnect();
		} catch (IOException e) {
			log.error("IO error.", e);
			disconnect();
		} catch (Exception e) {
			log.error(e);
			disconnect();
		}
	}
	
	protected void sendFile(String filePath) {
		File file = new File(filePath);
		
		// Making sure the file exists
		if(!file.exists()) {
			log.error("Cannot send file.", new FileNotFoundException("File " + filePath + " does not exist."));
			disconnect();
			return;
		}
		
		// Making sure the file isn't above an allowed limit
		long size = file.length();
		
		if(size > MAX_SIZE_ALLOWED) {
			log.error("File size of " + (size / MEGA_BYTE) + "MB is above the allowed limit of " + MAX_SIZE_ALLOWED + "MB. The file send will be CANCELLED", new Exception("File size too large."));
			disconnect();
			return;
		}
		
		// Sending file extension
		DataOutputStream dOutput = (DataOutputStream) getOutput(DataOutputStream.class);
		try {
			String fileName = file.getName();
			// Parse file name to find out extension type
			int dotIndex = fileName.lastIndexOf('.');
			String extension = "";
			if(dotIndex != -1) extension = fileName.substring(dotIndex, fileName.length());
			dOutput.writeUTF(extension);
			dOutput.flush();
		} catch(IOException e) {
			log.error("IO Error when sending file extension.", e);
			disconnect();
			return;
		}
		
		// Sending file size
		try {
			dOutput.writeLong(size);
			dOutput.flush();
		} catch(IOException e) {
			log.error("IO error sending file size.", e);
			disconnect();
			return;
		}
		
		try (
				// For reading/loading the file into RAM
				FileInputStream fInput = new FileInputStream(file);
				BufferedInputStream bInput = new BufferedInputStream(fInput)
			) {
			log.debug("Preparing to send file...");
			
			// Used for the buffer size
			int bufferSize = 1024 * 8;
			byte[] bytes = new byte[bufferSize];
			
			// For sending the file
			final BufferedOutputStream bOutput = (BufferedOutputStream) getOutput(BufferedOutputStream.class);
			bOutput.flush();
			
			log.debug("Sending " + (size / 1000.0) + "kb file...");
			//log.info("==============================================");
			
			// Reading the data with read() and sending it with write()
			// -1 from read() means the end of stream (no more bytes to read)
			for(int count; (count = bInput.read(bytes)) != -1;) {
				// count is the number of bytes to write,
				// 0 is the offset
				// bytes is the actual data to write
				bOutput.write(bytes, 0, count);
				//log.info("Sent " + count + " bytes.");
			}
			bOutput.flush();
			
			//log.info("==============================================");
			log.debug("Done.");
			
		} catch (FileNotFoundException e) {
			log.error("File not found error. ", e);
			disconnect();
		} catch (IOException e) {
			log.error("IO Error.", e);
			disconnect();
		}
	}
	
	public synchronized void disconnect() {
		// Only disconnect if the connection is not closed
		if(isClosed()) return;
		
		log.debug("*** Disconnecting from " + socket.getInetAddress() + " ***");
		
		log.debug("Closing input streams...");
		for(InputStream stream: inStreams) {
			try {
				stream.close();
			} catch (IOException e) {
				log.error("Error closing input stream with [" + socket.getInetAddress() + "].", e);
			}
		}
		log.debug("Done.");
		
		log.debug("Closing output streams...");
		for(OutputStream stream: outStreams) {
			try {
				stream.close();
			} catch (IOException e) {
				log.error("Error closing output stream with [" + socket.getInetAddress() + "].", e);
			}
		}
		log.debug("Done.");
		
		try {
			// Close the socket and its associated input/output streams
			socket.close();
		}
		catch(IOException e) {
			log.error("Error closing socket with [" + socket.getInetAddress() + "].", e);
		}
		log.debug("*** Done with " + socket.getInetAddress() + " ***");
	}
	
	public boolean isClosed() {
		return socket.isClosed();
	}
	
	public InetAddress getInetAddress() {
		return socket.getInetAddress();
	}
}