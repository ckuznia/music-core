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
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import music.core.binarytree.BinaryTree;

public abstract class AbstractConnection {

	private static final Logger log = LogManager.getLogger(AbstractConnection.class);
	
	// Different messages clients can send to the server, these messages refer to specific commands
	protected enum Message {DISCONNECT, ACK, LIBRARY, DATABASE_ADD, DATABASE_RETRIEVE, DATABASE_STREAM};
	
	private final Socket socket;
	// TODO: add second socket. Each connection should be a pair of sockets, one for sending byte
	// data (streaming/downloading/etc), another for all other data.
	
	// Lists for input and output streams that are used (besides the basic InputStream/OutputStream).
	// Storing them in a list and closing them in a loop ensures all streams have been closed.
	private final ArrayList<InputStream> inStreams = new ArrayList<InputStream>();
	private final ArrayList<OutputStream> outStreams = new ArrayList<OutputStream>();
	
	public AbstractConnection(Socket socket) {
		this.socket = socket;
		
		try {
			/*
			 * Input streams block until their corresponding output streams have "written and flushed the header".
			 * Therefore output streams are setup first, and then their corresponding input streams.
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
		// For reading file size and file extension
		final DataInputStream dInput = (DataInputStream) getInput(DataInputStream.class);
		
		// Retrieving file size
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
		// The file will be renamed after processed by Storage.
		DateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy_HH-mm-ss");
		Date date = new Date();
		String name = dateFormat.format(date) + extension;
		
		try(
			// For writing the incoming file to the hard drive
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
			
			// Reading from the input stream and saving to a file	
			int bytesReceived = 0;
			for(int count; bytesReceived < fileSize && (count = bInput.read(bytes)) != -1;) {
				// bytes is the actual data to write,
				// 0 is the offset,
				// count is the number of bytes to write
				bOutput.write(bytes, 0, count);
				bytesReceived += count;
			}
			bOutput.flush();
			fOutput.flush();
			
			log.debug("Done.");
		} catch (EOFException e) {
			log.error("End of file error.", e);
			disconnect();
		} catch (IOException e) {
			log.error("IO error.", e);
			disconnect();
		}
		
		// Send acknowledgement
		writeInt(Message.ACK.ordinal());
	}
	
	protected void writeFile(String filePath, boolean streaming) {
		// For sending file size and file extension
		DataOutputStream dOutput = (DataOutputStream) getOutput(DataOutputStream.class);
		File file = new File(filePath);
		long fileSize = file.length();
		
		// Making sure the file exists
		if(!file.exists()) {
			log.error("Cannot send file. File " + filePath + " does not exist.");
			disconnect();
			return;
		}
		
		// If client is not intending to stream the data, the file size and file extension
		// data will be sent, otherwise skip these steps.
		if(!streaming) {
			// Sending file size
			try {
				dOutput.writeLong(fileSize);
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
				log.error("IO Error when parsing & sending file extension.", e);
				disconnect();
				return;
			}
		}
		
		try (
				// For reading the file in RAM
				FileInputStream fInput = new FileInputStream(file);
				BufferedInputStream bInput = new BufferedInputStream(fInput)
			) {			
			// Declaring buffer size
			int bufferSize = 1024 * 8;
			byte[] bytes = new byte[bufferSize];
			
			// For writing the file to the output stream
			final BufferedOutputStream bOutput = (BufferedOutputStream) getOutput(BufferedOutputStream.class);
			bOutput.flush();
			
			log.debug("Sending " + (fileSize / 1000.0) + "kb file...");
			
			// Reading the data with read() and sending it with write()
			// -1 from read() means the end of stream (no more bytes to read)
			for(int count; (count = bInput.read(bytes)) != -1;) {
				// bytes is the actual data to write,
				// 0 is the offset,
				// count is the number of bytes to write
				bOutput.write(bytes, 0, count);
			}
			bOutput.flush();
			
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
	
	protected synchronized void playLocal() {
		AudioInputStream ais = null;
		try {
			// TODO: This will not use sockets if playing locally, FileInputStream most
			// likely what will be used
			ais = AudioSystem.getAudioInputStream(new BufferedInputStream(socket.getInputStream())); 
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
	
	protected synchronized void stream() {
		try (
				// For streaming the incoming bytes
				BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
				AudioInputStream ais = AudioSystem.getAudioInputStream(bis);
			){
			
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, ais.getFormat());
			SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(ais.getFormat());
			line.flush();
			line.start();
			
			// Declaring buffer size
			byte[] buffer = new byte[3 * 1024 * ais.getFormat().getFrameSize()];
			
			log.debug("Streaming bytes...");
			
			// Streaming bytes
			for(int count; (count = ais.read(buffer)) != -1;) {
				// buffer is the array containing data to be written
				// 0 is the offset,
				// count is the number of bytes to write
				line.write(buffer, 0, count);
			}
			
			// Close resources
			line.drain();
			line.stop();
			line.close();
			ais.close();
		} catch(Exception e) {
			log.error("ERROR in playRemote()", e);
		}
		
		// Send acknowledgement
		writeInt(Message.ACK.ordinal());
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