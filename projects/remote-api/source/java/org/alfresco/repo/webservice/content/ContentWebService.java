/*
 * Copyright (C) 2005 Alfresco, Inc.
 *
 * Licensed under the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 * You may obtain a copy of the License at
 *
 *     http://www.gnu.org/licenses/lgpl.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.alfresco.repo.webservice.content;

import java.io.Serializable;
import java.net.URLEncoder;
import java.rmi.RemoteException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.UserTransaction;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.webservice.Utils;
import org.alfresco.repo.webservice.types.Content;
import org.alfresco.repo.webservice.types.ContentFormat;
import org.alfresco.repo.webservice.types.ParentReference;
import org.alfresco.repo.webservice.types.Predicate;
import org.alfresco.repo.webservice.types.Reference;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.apache.axis.MessageContext;
import org.apache.axis.transport.http.HTTPConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Web service implementation of the ContentService.
 * The WSDL for this service can be accessed from http://localhost:8080/alfresco/api/ContentService?wsdl
 *  
 * @author gavinc
 */
public class ContentWebService implements ContentServiceSoapPort
{
   private static Log logger = LogFactory.getLog(ContentWebService.class);
   private static final String BROWSER_URL   = "{0}://{1}{2}/download/direct/{3}/{4}/{5}/{6}";
   
   private NodeService nodeService;
   private ContentService contentService;
   
   /**
    * Sets the instance of the NodeService to be used
    * 
    * @param nodeService The NodeService
    */
   public void setNodeService(NodeService nodeService)
   {
      this.nodeService = nodeService;
   }
   
   /**
    * Sets the ContentService instance to use
    * 
    * @param contentSvc The ContentService
    */
   public void setContentService(ContentService contentSvc)
   {
      this.contentService = contentSvc;
   }

   /**
    * @see org.alfresco.repo.webservice.content.ContentServiceSoapPort#describe(org.alfresco.repo.webservice.types.Predicate)
    */
   public Content[] describe(Predicate items) throws RemoteException, ContentFault
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Utils.getUserTransaction(MessageContext.getCurrentContext());
         tx.begin();
         
         List<NodeRef> nodes = Utils.resolvePredicate(items);
         Content[] descriptions = new Content[nodes.size()];
         
         for (int x = 0; x < nodes.size(); x++)
         {
            descriptions[x] = setupContentObject(nodes.get(x));
         }
         
         // commit the transaction
         tx.commit();
         
         return descriptions;
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         
         if (logger.isDebugEnabled())
         {
            logger.error("Unexpected error occurred", e);
         }
         
         throw new ContentFault(0, e.getMessage());
      }
   }

   /**
    * @see org.alfresco.repo.webservice.content.ContentServiceSoapPort#read(org.alfresco.repo.webservice.types.Reference)
    */
   public ReadResult read(Reference node) throws RemoteException, ContentFault
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Utils.getUserTransaction(MessageContext.getCurrentContext());
         tx.begin();
         
         // retrieve metadata for given node
         NodeRef nodeRef = Utils.convertToNodeRef(node);
         Map<QName, Serializable> props = this.nodeService.getProperties(nodeRef);
         String filename = (String)props.get(ContentModel.PROP_NAME);
         Content content = setupContentObject(nodeRef);
         
         if (logger.isDebugEnabled())
         {
            logger.debug("Reading content: " + node.getUuid() + " name=" + filename + 
                  " encoding=" + content.getFormat().getEncoding() + 
                  " mimetype=" + content.getFormat().getMimetype() + 
                  " size=" + content.getLength());
         }
         
         // work out what the server, port and context path are
         HttpServletRequest req = (HttpServletRequest)MessageContext.getCurrentContext().
            getProperty(HTTPConstants.MC_HTTP_SERVLETREQUEST);
         
         String address = req.getLocalName();
         if (req.getLocalPort() != 80)
         {
            address = address + ":" + req.getLocalPort();
         }
         
         // format the URL that can be used to download the content
         String downloadUrl =  MessageFormat.format(BROWSER_URL, new Object[] {
            req.getScheme(), address, req.getContextPath(),
            nodeRef.getStoreRef().getProtocol(),
            nodeRef.getStoreRef().getIdentifier(),
            nodeRef.getId(), URLEncoder.encode(filename, "UTF-8")} );
         
         if (logger.isDebugEnabled())
            logger.debug("Generated download URL: " + downloadUrl);
         
         // commit the transaction
         tx.commit();
         
         return new ReadResult(downloadUrl, content);
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         
         if (logger.isDebugEnabled())
         {
            logger.error("Unexpected error occurred", e);
         }
         
         throw new ContentFault(0, e.getMessage());
      }
   }

   /**
    * @see org.alfresco.repo.webservice.content.ContentServiceSoapPort#write(org.alfresco.repo.webservice.types.Reference, byte[])
    */
   public void write(Reference node, byte[] content) throws RemoteException, ContentFault
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Utils.getUserTransaction(MessageContext.getCurrentContext());
         tx.begin();
         
         // create a NodeRef from the parent reference
         NodeRef nodeRef = Utils.convertToNodeRef(node);
         
         ContentWriter writer = this.contentService.getUpdatingWriter(nodeRef);
         writer.putContent(new String(content));
         
         if (logger.isDebugEnabled())
            logger.debug("Updated content for node with id: " + nodeRef.getId());
         
         // commit the transaction
         tx.commit();
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         
         if (logger.isDebugEnabled())
         {
            logger.error("Unexpected error occurred", e);
         }
         
         throw new ContentFault(0, e.getMessage());
      }
   }

   /**
    * @see org.alfresco.repo.webservice.content.ContentServiceSoapPort#create(org.alfresco.repo.webservice.types.ParentReference, java.lang.String, org.alfresco.repo.webservice.types.ContentFormat, byte[])
    */
   public Content create(ParentReference parent, String name, ContentFormat format, byte[] content) throws RemoteException, ContentFault
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Utils.getUserTransaction(MessageContext.getCurrentContext());
         tx.begin();
         
         // create a NodeRef from the parent reference
         // TODO: handle creation from a path
         NodeRef parentNodeRef = new NodeRef(Utils.convertToStoreRef(parent.getStore()), parent.getUuid());
         
         Map<QName, Serializable> contentProps = new HashMap<QName, Serializable>(5, 1.0f);
         contentProps.put(ContentModel.PROP_NAME, name);
         contentProps.put(ContentModel.PROP_ENCODING, format.getEncoding());
         contentProps.put(ContentModel.PROP_MIME_TYPE, format.getMimetype());
         
         // create the node to represent the content
         String assocName = QName.createValidLocalName(name);
         ChildAssociationRef assocRef = this.nodeService.createNode(
               parentNodeRef,
               ContentModel.ASSOC_CONTAINS,
               QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, assocName),
               ContentModel.TYPE_CONTENT,
               contentProps);
         
         NodeRef contentNodeRef = assocRef.getChildRef();
         
         if (logger.isDebugEnabled())
            logger.debug("Created content node (name=" + name + " id=" + contentNodeRef.getId() + ")");
         
         // get a writer for the content and put the file
         ContentWriter writer = contentService.getUpdatingWriter(contentNodeRef);
         writer.putContent(new String(content));
         
         // create the return object
         Content contentReturn = setupContentObject(contentNodeRef); 
         
         // commit the transaction
         tx.commit();
         
         return contentReturn; 
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         
         if (logger.isDebugEnabled())
         {
            logger.error("Unexpected error occurred", e);
         }
         
         throw new ContentFault(0, e.getMessage());
      }
   }

   /**
    * @see org.alfresco.repo.webservice.content.ContentServiceSoapPort#delete(org.alfresco.repo.webservice.types.Predicate)
    */
   public Reference[] delete(Predicate items) throws RemoteException, ContentFault
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Utils.getUserTransaction(MessageContext.getCurrentContext());
         tx.begin();
         
         List<NodeRef> nodes = Utils.resolvePredicate(items);
         Reference[] refs = new Reference[nodes.size()];
         
         // delete each node in the predicate
         for (int x = 0; x < nodes.size(); x++)
         {
            NodeRef nodeRef = nodes.get(x);
            this.nodeService.deleteNode(nodeRef);
            
            if (logger.isDebugEnabled())
               logger.debug("Deleted content node with id: " + nodeRef.getId());
            
            refs[x] = Utils.convertToReference(nodeRef);
         }
         
         // commit the transaction
         tx.commit();
         
         return refs; 
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         
         if (logger.isDebugEnabled())
         {
            logger.error("Unexpected error occurred", e);
         }
         
         throw new ContentFault(0, e.getMessage());
      }
   }

   /**
    * @see org.alfresco.repo.webservice.content.ContentServiceSoapPort#exists(org.alfresco.repo.webservice.types.Predicate)
    */
   public ExistsResult[] exists(Predicate items) throws RemoteException, ContentFault
   {
      UserTransaction tx = null;
      
      try
      {
         // resolve the predicate to a list of NodeRef objects
         tx = Utils.getUserTransaction(MessageContext.getCurrentContext());
         tx.begin();
         
         List<NodeRef> nodes = Utils.resolvePredicate(items);
         ExistsResult[] existsReturn = new ExistsResult[nodes.size()];
         
         // query whether each NodeRef exists in the repository
         for (int x = 0; x < nodes.size(); x++)
         {
            NodeRef nodeRef = nodes.get(x);
            boolean exists = this.nodeService.exists(nodeRef);
            long length = -1;
            if (exists)
            {
               Long size = (Long)this.nodeService.getProperty(nodeRef, ContentModel.PROP_SIZE);
               if (size != null && size instanceof Long)
               {
                  length = ((Long)size).longValue();
               }
            }
            
            ExistsResult result = new ExistsResult(Utils.convertToReference(nodeRef), exists, length);
            existsReturn[x] = result;
         }
         
         tx.commit();
         
         return existsReturn; 
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         
         if (logger.isDebugEnabled())
         {
            logger.error("Unexpected error occurred", e);
         }
         
         throw new ContentFault(0, e.getMessage());
      }
   }
   
   /**
    * Sets up the returned Content representation for the given node
    *  
    * @param repoNode The node to create a Content representation of
    * @return Content object representing the given node
    */
   private Content setupContentObject(NodeRef node)
   {
      // get metadata for the given node
      Map<QName, Serializable> props = this.nodeService.getProperties(node);
      String encoding = (String)props.get(ContentModel.PROP_ENCODING);
      String mimetype = (String)props.get(ContentModel.PROP_MIME_TYPE);
      Object size = props.get(ContentModel.PROP_SIZE);
      long length = -1;
      if (size != null && size instanceof Long)
      {
         length = ((Long)size).longValue();
      }
         
      // setup the return objects
      ContentFormat format = new ContentFormat(mimetype, encoding);
      Content content = new Content(format, length);
      content.setReference(Utils.convertToReference(node));
      content.setType(this.nodeService.getType(node).toString());
      
      return content;
   }
}
