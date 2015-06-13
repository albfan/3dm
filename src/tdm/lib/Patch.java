// $Id: Patch.java,v 1.11 2004/03/08 12:16:20 ctl Exp $ D
//
// Copyright (c) 2001, Tancred Lindholm <ctl@cs.hut.fi>
//
// This file is part of 3DM.
//
// 3DM is free software; you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation; either version 2.1 of the License, or
// (at your option) any later version.
//
// 3DM is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with 3DM; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
package tdm.lib;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Vector;
import java.util.LinkedList;
import org.xml.sax.SAXException;
import org.xml.sax.ContentHandler;

/** Patching algorithm.
 *  See the {@link patch(BaseNode, BranchNode, org.xml.sax.ContentHandler)
 *  patch} method for usage.
 */

public class Patch {

  public Patch() {
  }

  /** Patch a tree.
   *  @param base Tree to patch
   *  @param diff Parse tree of XML diff file, as produced by the Diff class
   *  @param ch Content handler that the patched tree is output to
   */

  public void patch( BaseNode base, BranchNode diff, ContentHandler ch )
    throws ParseException, SAXException {
    BranchNode patched = patch( base, diff );
    ch.startDocument();
    dumpTree( patched.getChild(0), ch );
    ch.endDocument();
  }

  private void dumpTree( BranchNode n, ContentHandler ch ) throws SAXException {
    if( n.getContent() instanceof XMLTextNode ) {
      char [] text = ((XMLTextNode) n.getContent()).getText();
      ch.characters(text,0,text.length);
    } else {
      XMLElementNode en = (XMLElementNode) n.getContent();
      ch.startElement("","",en.getQName(),en.getAttributes());
      for( int i=0;i<n.getChildCount();i++)
        dumpTree(n.getChild(i),ch );
      ch.endElement("","",en.getQName());
    }
  }

  protected BranchNode patch( BaseNode base, BranchNode diff ) throws
          ParseException  {
    initLookup(base,nodeLookup);
    BranchNode patch = new BranchNode( new XMLElementNode("$DUMMY$",
      new org.xml.sax.helpers.AttributesImpl() ) );
    // Getchild(0) to skip diff tag
    // Note that we need an $DUMMY$ node to which $ROOT$ is attached as an only child
    // we cant use <root> as start of copy, since root elem may be changed!
    copy( patch,diff.getChild(0),base,0);
    return patch.getChild(0);
  }

  // diff => a command or just some nodes to insert
  // patch = the parent node, under which the subtree produced by the command
  // shall be inserted

  protected void insert( BranchNode patch, BranchNode diff) throws
    ParseException {
    XMLNode cmdcontent = diff.getContent();
    if( cmdcontent instanceof XMLTextNode ||
      !Diff.RESERVED.contains(((XMLElementNode) cmdcontent).getQName())) {
      // Simple insert operation
      BranchNode node = new BranchNode( cmdcontent );
      patch.addChild(node);
      for( int i=0;i<diff.getChildCount();i++)
        insert(node,diff.getChild(i)); // Recurse to next level
    } else {
      // Other ops..
      XMLElementNode ce = (XMLElementNode) cmdcontent;
      if( ce.getQName().equals(Diff.DIFF_NS+"esc") ||
          ce.getQName().equals(Diff.DIFF_NS+"insert") ) {
        // BUGFIX 030115
	  //if( diff.getChildCount() == 0)
          //throw new ParseException("DIFFSYNTAX: insert/esc has no subtree " +
          //                          ce.toString() );
        //ENDBUGFIX
        for( int i=0;i<diff.getChildCount();i++) // SHORTINS
          insert( patch, diff.getChild(i) );
      } else {
        // Copy operation
        BaseNode srcRoot = null;
        int src = -1;
        try {
          src = Integer.parseInt(ce.getAttributes().getValue("src"));
          srcRoot = locateNode(src) ;
        } catch ( Exception e ) {
            throw new ParseException(
              "DIFFSYNTAX: Invalid parameters in command " + ce.toString() );
        }
        copy( patch, diff, srcRoot,src );
      } // Copyop
    }
  }

  // Called on copy tag
  protected void copy( BranchNode patch, BranchNode diff, BaseNode srcRoot, int src )
                      throws ParseException {
    // Gather the stopnodes for the copy
    Vector dstNodes = new Vector();
    Map stopNodes = new HashMap();
    int run = 1;
    try {
      String runS = ((XMLElementNode) diff.getContent()).getAttributes().getValue("run");
      if (runS != null)
        run = Integer.parseInt(runS);
    } catch ( Exception e ) {
      throw new ParseException("DIFFSYNTAX: Invalid run count for copy tag " +
                               diff.toString() );
    }

    for( int i = 0; i < diff.getChildCount(); i++ ) {
      XMLNode content = diff.getChild(i).getContent();
      Node stopNode = null;
      // Sanity check
      if( content instanceof XMLTextNode || (
        !((XMLElementNode) content).getQName().equals(Diff.DIFF_NS+"copy") &&
        !((XMLElementNode) content).getQName().equals(Diff.DIFF_NS+"insert") ) )
        throw new ParseException("DIFFSYNTAX: Only copy or insert commands may"+
                                 " appear below a copy command");
      XMLElementNode stopCommand = (XMLElementNode) content;
      try {
        stopNode = locateNode( Integer.parseInt(
                                stopCommand.getAttributes().getValue("dst")));
      } catch ( Exception e ) {
        throw new ParseException("DIFFSYNTAX: Invalid parameters in command " +
                                 stopNode.toString() );
      }
      dstNodes.add( stopNode );
      if( !stopNodes.containsKey(stopNode) )
        stopNodes.put(stopNode,null);
    }
    // Run copy. stopNode values are at the same time filled in to point to the
    // created nodes
    for( int iRun=1;iRun<run;iRun++) {
      dfsCopy(patch,srcRoot,null);
      srcRoot = locateNode(src+iRun);
    }
    dfsCopy( patch, srcRoot , stopNodes );
    // Recurse for each diff child
    for( int i = 0; i < diff.getChildCount(); i++ ) {
      // DEBUG
      /*BranchNode _n = (BranchNode) stopNodes.get( dstNodes.elementAt(i) );
      if(  _n == null  ) {
        BaseNode _s = (BaseNode) dstNodes.elementAt(i);
        System.err.println("Internal failure: Stop node not created");
        System.err.println("src path=" + PathTracker.getPathString(srcRoot) );
        System.err.println("stop path=" + PathTracker.getPathString(_s ));
      }*/
      //ENDDEBUG
      insert( (BranchNode) stopNodes.get( dstNodes.elementAt(i) ),
              diff.getChild(i));
    }
  }

  protected void dfsCopy( BranchNode dst, BaseNode src, Map stopNodes ) {
    BranchNode copied = new BranchNode( src.getContent() );
    dst.addChild( copied );
    if( stopNodes != null && stopNodes.containsKey(src) ) {
      stopNodes.put(src,copied);
      return; // We're done in this branch
    }
    for( int i =0;i<src.getChildCount();i++)
      dfsCopy( copied, src.getChild(i), stopNodes );
  }

  private Vector nodeLookup = new Vector();

  protected BaseNode locateNode( int ix ) {
    return (BaseNode) nodeLookup.elementAt(ix);
  }

  // BFS Enumeration of nodes. Useful beacuse adjacent nodes have subsequent ids =>
  // diff can use the "run" attribute more often
  // NOTE: Copied from diff, should really be moved to a common base class for
  // patch & diff
  protected void initLookup( Node start, Vector table) {
    LinkedList queue = new LinkedList();
    queue.add(start);
    while( !queue.isEmpty() ) {
      Node n = (Node) queue.removeFirst();
      table.add(n);
      for( int i=0;i<n.getChildCount();i++)
        queue.add(n.getChildAsNode(i));
    }
  }
}
