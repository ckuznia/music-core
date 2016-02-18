package music.core.binarytree;

import java.io.Serializable;
import java.util.ArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import music.core.Track;

public class BinaryTree implements Serializable {
	
	private static final long serialVersionUID = 1L;

	private static final Logger log = LogManager.getLogger(BinaryTree.class);
	
	private Node root;
	private ArrayList<Node> nodes = new ArrayList<Node>();
	
	public void add(Track track) {
		Node nodeToAdd = new Node(track);
		
		// Check if root node exists
		if(root == null) {
			root = nodeToAdd;
			nodes.add(root);
		}
		else traverseAndAddNode(root, nodeToAdd);
	}
	
	/**
	 * A "smart" add function that adds multiple tracks to the binary tree in a way that attempts to keep the binary tree efficient. This 
	 * assumes the tracks are in alphabetical order.
	 * 
	 * <p>Since the the array of Tracks is in alphabetic order, a good way to populate the tree is
	 * to put the middle track as the root. This leaves two track arrays, the tracks on the left of the index,
	 * and those on the right of the index. The left and right tracks are also split in the middle (using the
	 * middle track as the parent node). This process
	 * is repeated until all tracks have been added to the tree.
	 * 
	 * @param tracks the tracks to be added
	 */
	public void add(Track[] tracks) {
		// Finding the middle track in the current track array
		int midIndex = (int)(tracks.length / 2);
		// Adding the middle track to the tree
		add(tracks[midIndex]);
		
		// Creating a list of tracks that will be on the left side of the middle track
		Track[] leftTracks = new Track[midIndex];
		// Creating a list of tracks that will be on the right side of the middle track
		Track[] rightTracks = new Track[tracks.length - (midIndex + 1)]; 
		
		if(leftTracks.length > 0) {
			// Populating left tracks
			for(int n = 0; n < leftTracks.length; n++) leftTracks[n] = tracks[n];
			// Continue to break the track array up
			add(leftTracks);
		}
		if(rightTracks.length > 0) {
			// Populating right tracks
			for(int n = 0, offset = midIndex + 1; n < rightTracks.length; n++) rightTracks[n] = tracks[n + offset];
			// Continue to break the track array up
			add(rightTracks);
		}
	}
	
	private void traverseAndAddNode(Node currentNode, Node nodeToAdd) {
		if(compare(nodeToAdd, currentNode) < 0) {
			// If leftChild does not exist
			if(currentNode.getLeftChild() == null) {
				nodeToAdd.setParent(currentNode);
				currentNode.setLeftChild(nodeToAdd);
				nodes.add(nodeToAdd);
			}
			// Traverse the left child
			else traverseAndAddNode(currentNode.getLeftChild(), nodeToAdd);
		}
		else if(compare(nodeToAdd, currentNode) > 0) {
			// If rightChild does not exist
			if(currentNode.getRightChild() == null) {
				nodeToAdd.setParent(currentNode);
				currentNode.setRightChild(nodeToAdd);
				nodes.add(nodeToAdd);
			}
			// Traverse the right child
			else traverseAndAddNode(currentNode.getRightChild(), nodeToAdd);
		}
		else {
			// Node already exists in database
			log.debug("Data, " + nodeToAdd.getTrack().getID() + ", already exists in database. No action needed.");
		}
	}
	
	public ArrayList<Track> preOrderTraversal() {
		// pre-order, in-order, post-order
		ArrayList<Track> preOrderTrack = new ArrayList<Track>(nodes.size());
		if(root != null) {
			preOrderTraversal(root, preOrderTrack);
		}
		return preOrderTrack;
	}
	
	private void preOrderTraversal(Node node, ArrayList<Track> tracks) {
		// Add the track to the list of tracks
		tracks.add(node.getTrack());
		
		// Keep going to the left child of every node until the
		// bottom-left of the tree is hit
		if(node.getLeftChild() != null) {
			// This will get the leftChild's data
			preOrderTraversal(node.getLeftChild(), tracks);
		}
		
		if(node.getRightChild() != null) {
			// This will get the rightChil's data
			preOrderTraversal(node.getRightChild(), tracks);
		}
	}
	
	public void delete(Track track) {
		Node nodeToDelete = find(track);
		if(nodeToDelete == null) {
			log.debug("Node with data " + track + " was not found. No action needed.");
			return;
		}
		
		// case 1: node has no children
		else if(nodeToDelete.getLeftChild() == null && nodeToDelete.getRightChild() == null) {
			deleteNoChild(nodeToDelete);
		}
		// case 2: node has two children
		else if(nodeToDelete.getLeftChild() != null && nodeToDelete.getRightChild() != null) {
			deleteTwoChildren(nodeToDelete);
		}
		// case 3: node has one child
		else deleteOneChild(nodeToDelete);
		
		// Remove the node from the list of nodes
		nodes.remove(nodes.indexOf(nodeToDelete));
	}
	
	private void deleteNoChild(Node nodeToDelete) {
		Node parent = nodeToDelete.getParent();
		// If nodeToDelete is right child
		if(nodeToDelete.equals(parent.getLeftChild())) parent.setLeftChild(null);
		// If nodeToDelete is right child
		else if(nodeToDelete.equals(parent.getRightChild())) parent.setRightChild(null);
	}
	
	private void deleteOneChild(Node nodeToDelete) {
		// Delete by making the nodeToDelete's parent node point to the
		// nodeToDelete's child nodes
		Node parent = nodeToDelete.getParent();
		// If nodeToDelete is left child
		if(nodeToDelete.equals(parent.getLeftChild())) {
			// Unsure if left or right child is null, so must check
			if(nodeToDelete.getLeftChild() != null) {
				// Making parent node point to child of deleted node
				parent.setLeftChild(nodeToDelete.getLeftChild());
				// Making child of deleted node have correct parent
				parent.getLeftChild().setParent(parent);
			}
			else {
				// Making parent node point to child of deleted node
				parent.setLeftChild(nodeToDelete.getRightChild());
				// Making child of deleted node have correct parent
				parent.getLeftChild().setParent(parent);
			}
		}
		// If nodeToDelete is right child
		else if(nodeToDelete.equals(parent.getRightChild())) {
			// Unsure if left or right child is null, so must check
			if(nodeToDelete.getLeftChild() != null) {
				// Making parent node point to child of deleted node
				parent.setRightChild(nodeToDelete.getLeftChild());
				// Making child of deleted node have correct parent
				parent.getRightChild().setParent(parent);
			}
			else {
				// Making parent node point to child of deleted node
				parent.setRightChild(nodeToDelete.getRightChild());
				// Making child of deleted node have correct parent
				parent.getRightChild().setParent(parent);
			}
		}
	}
	
	private void deleteTwoChildren(Node nodeToDelete) {
		// Delete by looking at the nodeToDelete's right child, and then
		// traversing all the way down to the left, and then replace nodeToDelete 
		// with the node found at the bottom (Essentially the node with the value closest to the nodeToDelete)
		// After this the tree should still be correct
		
		// This is the node found at the bottom of the right child
		Node minNode = minLeftTraversal(nodeToDelete.getRightChild());
		
		// Now delete that bottom node
		deleteOneChild(minNode);
		
		Node parent = nodeToDelete.getParent();
		// Copy all properties
		minNode.setParent(parent); // Assign parent
		minNode.setLeftChild(nodeToDelete.getLeftChild()); // Assign left child
		minNode.setRightChild(nodeToDelete.getRightChild()); // Assign right child
		minNode.getLeftChild().setParent(minNode); // Assign left child's parent
		minNode.getRightChild().setParent(minNode); // Assign right child's parent
		
		// Special case, nodeToDelete is the root and therefore has no parent
		if(nodeToDelete.getParent() == null) {
			root = minNode;
		}
		else {
			// If nodeToDelete is left child
			if(nodeToDelete.equals(parent.getLeftChild())) {
				// switch the node
				parent.setLeftChild(minNode);
			}
			// If nodeToDelete is right child
			else if(nodeToDelete.equals(parent.getRightChild())) {
				// Now switch the node
				parent.setRightChild(minNode);
			}
		}
	}
	
	private Node minLeftTraversal(Node node) {
		// Go to the left-most node under the node passed in
		if(node.getLeftChild() == null) return node;
		return minLeftTraversal(node.getLeftChild());
	}
	
	public Node find(Track track) {
		return findNode(root, new Node(track));
	}
	
	private Node findNode(Node search, Node nodeToFind) {
		if(search == null) return null;
		
		if(compare(nodeToFind, search) < 0) return findNode(search.getLeftChild(), nodeToFind);
		if(compare(nodeToFind, search) > 0) return findNode(search.getRightChild(), nodeToFind);
		// search and node have equal data
		return search; // NOTE: must return search, NOT nodeToFind (as search has the full data for the Node object)
	}
	
	private int compare(final Node node1, final Node node2) {
		String ID1 = node1.getTrack().getID();
		String ID2 = node2.getTrack().getID();
		return ID1.compareTo(ID2);
	}
	
	public ArrayList<Node> getNodes() {
		return nodes;
	}
	
	public int getSize() {
		return nodes.size();
	}
}
