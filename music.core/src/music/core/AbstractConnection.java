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

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import music.core.binarytree.BinaryTree;

public abstract class AbstractConnection {

	private static final Logger log = LogManager.getLogger(AbstractConnection.class);
	
	protected enum Message {DISCONNECT, ACK, LIBRARY, DATABASE_ADD, DATABASE_RETRIEVE};
	
	private final Socket socket;
	
	// Lists for input and output streams that are used (besides the basic InputStream/OutputStream).
	// Storing them in a list and closing them in a loop ensures all streams have been closed.
	private final ArrayList<InputStream> inStreams = new ArrayList<InputStream>();
	private final ArrayList<OutputStream> outStreams = new ArrayList<OutputStream>();
	
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
	
	protected int basicReadInt() {
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
	
	private void basicWriteInt(int value) {
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
	
	protected int readInt() {
		// Read integer like normal
		int value = basicReadInt();
		
		// If value read is not an acknowledgement
		if(value != Message.ACK.ordinal()) {
			// Send an acknowledgement that value was read
			basicWriteInt(Message.ACK.ordinal());
		}
		return value;
	}
	
	protected void writeInt(int value) {
		// Send integer like normal
		basicWriteInt(value);
		
		// If value sent is not an acknowledgement
		if(value != Message.ACK.ordinal()) {
			// Wait for acknowledgement
			readACK();
		}	
	}
	
	protected void readACK() {
		int value = basicReadInt();
		if(value != Message.ACK.ordinal()) {
			log.error("ACK is incorrect, got " + value);
			disconnect();
		}
	}
	
	protected void readFile(String newPath) {
		final DataInputStream dInput = (DataInputStream) getInput(DataInputStream.class);
		
		// Getting file size
		long fileSize = -1;
		try {
			fileSize = dInput.readLong();
		} catch(IOException e) {
			log.error("IO Error getting file size from " + socket.getInetAddress(), e);
			disconnect();
			return;
		}
		
		// Retrieving file extension
		String extension = "";
		try {
			extension = dInput.readUTF();
		} catch (IOException e) {
			log.error("IO Error getting file extension.", e);
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
		
		// Send acknowledgement
		writeInt(Message.ACK.ordinal());
	}
	
	protected void test_readStream(String newPath) {
		final DataInputStream dInput = (DataInputStream) getInput(DataInputStream.class);
		
		// Getting file size
		long fileSize = -1;
		try {
			fileSize = dInput.readLong();
		} catch(IOException e) {
			log.error("IO Error getting file size from " + socket.getInetAddress(), e);
			disconnect();
			return;
		}
		
		// Retrieving file extension
		String extension = "";
		try {
			extension = dInput.readUTF();
		} catch (IOException e) {
			log.error("IO Error getting file extension.", e);
			disconnect();
			return;
		}
		
		// Creating name for file to be downloaded, name is based on current date and time
		DateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy_HH-mm-ss");
		Date date = new Date();
		String name = dateFormat.format(date) + extension;
		
		try {
			//fOutput.flush();
			//bOutput.flush();
						
			// For reading the incoming file stream
			//BufferedInputStream bInput = new BufferedInputStream(socket.getInputStream());
			
			/*
			 * BUG: audio file only starts streaming once entire file is downloaded (defeats purpose of streaming)
			 * 
			 * Need to use SourceDataLine instead of Clip to play sound, SourceDataLine is for streaming.
			 * When the client is playing downloaded songs on device, Clip should be used.
			 */
			play(socket.getInputStream());
			
			//InputStream in = new BufferedInputStream(socket.getInputStream());
            //play(in);
			
			
			//bOutput.flush();
			//fOutput.flush();
			
			//log.info("==============================================");
			log.debug("Done.");
		} catch (EOFException e) {
			log.error("End of file error.", e);
			disconnect();
			return;
		} catch (IOException e) {
			log.error("IO error.", e);
			disconnect();
			return;
		} catch (Exception e) {
			log.error(e);
			disconnect();
			return;
		}
		
		// Send acknowledgement
		writeInt(Message.ACK.ordinal());
	}
	
	private static synchronized void play(final InputStream in) throws Exception {
		AudioInputStream ais = null;
		try {
			log.debug("Instantiating AudioInputStream...");
			ais = AudioSystem.getAudioInputStream(new BufferedInputStream(in));
			log.debug("Done.");
			
			try (Clip clip = AudioSystem.getClip()) {
				log.debug("Playing clip...");
	            clip.open(ais);
	            clip.start();
	            Thread.sleep(100); // given clip.drain a chance to start
	            clip.drain();
	            log.debug("Done.");
	        } catch(Exception e) {
				log.error("AudioInputStream created, but Clip failed to play");
			}
		} catch(Exception e) {
			log.error("AudioInputStream FAILED to instantiate:", e);
		}
    }
	
	protected void writeFile(String filePath) {
		File file = new File(filePath);
		
		// Making sure the file exists
		if(!file.exists()) {
			log.error("Cannot send file. File " + filePath + " does not exist.");
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
		
		// Sending file size
		DataOutputStream dOutput = (DataOutputStream) getOutput(DataOutputStream.class);
		try {
			dOutput.writeLong(size);
			dOutput.flush();
		} catch(IOException e) {
			log.error("IO error sending file size.", e);
			disconnect();
			return;
		}
		
		// Sending file extension
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
		
		// Wait for acknowledgement
		readACK();
	}
	
	protected void test_writeStream(String filePath) {
		File file = new File(filePath);
		
		// Making sure the file exists
		if(!file.exists()) {
			log.error("Cannot send file. File " + filePath + " does not exist.");
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
		
		// Sending file size
		DataOutputStream dOutput = (DataOutputStream) getOutput(DataOutputStream.class);
		try {
			dOutput.writeLong(size);
			dOutput.flush();
		} catch(IOException e) {
			log.error("IO error sending file size.", e);
			disconnect();
			return;
		}
		
		// Sending file extension
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
		
		try (
				// The input that feeds into the output stream
				FileInputStream fInput = new FileInputStream(file);
				//BufferedInputStream bInput = new BufferedInputStream(fInput)
			) {
			log.debug("Streaming file...");
			
			// Used for the buffer size
			int bufferSize = 1024 * 8;
			byte[] bytes = new byte[bufferSize];
			
			// For sending the stream of data
			//final BufferedOutputStream bOutput = (BufferedOutputStream) getOutput(BufferedOutputStream.class);
			//bOutput.flush();
			
			// Reading the data with read() and sending it with write()
			// -1 from read() means the end of stream (no more bytes to read)
			for(int count; (count = fInput.read(bytes)) != -1;) {
				// count is the number of bytes to write,
				// 0 is the offset
				// bytes is the actual data to write
				socket.getOutputStream().write(bytes, 0, count);
			}
			socket.getOutputStream().flush();
			
			//log.info("==============================================");
			log.debug("Done.");
			
		} catch (FileNotFoundException e) {
			log.error("File not found error. ", e);
			disconnect();
			return;
		} catch (IOException e) {
			log.error("IO Error.", e);
			disconnect();
			return;
		}
		
		// Wait for acknowledgement
		readACK();
	}
	
	protected Object readObject() {
		final ObjectInputStream objIn = (ObjectInputStream) getInput(ObjectInputStream.class);
		Object object = null;
		try {
			object = objIn.readObject();
		} catch(ClassNotFoundException e) {
			log.error("Could not find Class.", e);
		} catch (IOException e) {
			log.error("IO Exception.", e);
		}
		
		// Send acknowledgement that object was read
		writeInt(Message.ACK.ordinal());
		return object;
	}
	
	protected void writeObject(Object object) {		
		final ObjectOutputStream objOut = (ObjectOutputStream) getOutput(ObjectOutputStream.class);
		try {
			objOut.flush();
			
			objOut.writeObject(object);
			objOut.flush();
			log.debug("Done.");
		} catch (IOException e) {
			log.error("IO Exception.", e);
		}
		
		// Wait for acknowledgement
		readACK();
	}
	
	public synchronized void disconnect() {
		// Disconnect only if the connection has not been closed
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