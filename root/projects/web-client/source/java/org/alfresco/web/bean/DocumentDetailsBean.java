/*
 * Copyright (C) 2005 Alfresco, Inc.
 *
 * Licensed under the Mozilla Public License version 1.1 
 * with a permitted attribution clause. You may obtain a
 * copy of the License at
 *
 *   http://www.alfresco.org/legal/license.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.alfresco.web.bean;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.faces.context.FacesContext;
import javax.faces.event.ActionEvent;
import javax.transaction.UserTransaction;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.cmr.coci.CheckOutCheckInService;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.CopyService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.TemplateNode;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionHistory;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.namespace.QName;
import org.alfresco.web.app.Application;
import org.alfresco.web.app.context.UIContextService;
import org.alfresco.web.app.servlet.DownloadContentServlet;
import org.alfresco.web.bean.actions.BaseActionWizard;
import org.alfresco.web.bean.repository.MapNode;
import org.alfresco.web.bean.repository.Node;
import org.alfresco.web.bean.repository.Repository;
import org.alfresco.web.ui.common.Utils;
import org.alfresco.web.ui.common.Utils.URLMode;
import org.alfresco.web.ui.common.component.UIActionLink;
import org.apache.log4j.Logger;

/**
 * Backing bean providing access to the details of a document
 * 
 * @author gavinc
 */
public class DocumentDetailsBean extends BaseDetailsBean
{
   private static final String OUTCOME_RETURN = "showDocDetails";
   
   private static final String MSG_HAS_FOLLOWING_CATEGORIES = "has_following_categories";
   private static final String MSG_NO_CATEGORIES_APPLIED = "no_categories_applied";
   private static final String MSG_ERROR_ASPECT_INLINEEDITABLE = "error_aspect_inlineeditable";
   private static final String MSG_ERROR_ASPECT_VERSIONING = "error_aspect_versioning";
   private static final String MSG_ERROR_ASPECT_CLASSIFY = "error_aspect_classify";
   private static final String MSG_ERROR_WORKFLOW_REJECT = "error_workflow_reject";
   private static final String MSG_ERROR_WORKFLOW_APPROVE = "error_workflow_approve";
   private static final String MSG_ERROR_UPDATE_SIMPLEWORKFLOW = "error_update_simpleworkflow";
   private static final String MSG_ERROR_UPDATE_CATEGORY = "error_update_category";

   private static Logger logger = Logger.getLogger(DocumentDetailsBean.class);
   
   protected LockService lockService;
   protected CopyService copyService;
   protected VersionService versionService;
   protected CheckOutCheckInService cociService;
   
   private Map<String, Serializable> workflowProperties;
   private NodeRef addedCategory;
   private List categories;


   // ------------------------------------------------------------------------------
   // Construction 
   
   /**
    * Default constructor
    */
   public DocumentDetailsBean()
   {
      // initial state of some panels that don't use the default
      panels.put("workflow-panel", false);
      panels.put("category-panel", false);
      panels.put("version-history-panel", false);
   }
   
   
   // ------------------------------------------------------------------------------
   // Bean property getters and setters 
   
   /**
    * Resets any state that may be held by this bean
    */
   public void reset()
   {
      // reset the workflow cache
      this.workflowProperties = null;
      
      // reset the category caches
      this.categories = null;
      this.addedCategory = null;
   }
   
   /**
    * Returns the URL to download content for the current document
    * 
    * @return Content url to download the current document
    */
   public String getUrl()
   {
      return (String)getDocument().getProperties().get("url");
   }
   
   /**
    * Returns the URL to the content for the current document
    *  
    * @return Content url to the current document
    */
   public String getBrowserUrl()
   {
      Node doc = getLinkResolvedNode();
      return Utils.generateURL(FacesContext.getCurrentInstance(), doc, URLMode.HTTP_INLINE);
   }

   /**
    * Returns the download URL to the content for the current document
    *  
    * @return Download url to the current document
    */
   public String getDownloadUrl()
   {
      Node doc = getLinkResolvedNode();
      return Utils.generateURL(FacesContext.getCurrentInstance(), doc, URLMode.HTTP_DOWNLOAD);
   }
   
   /**
    * Resolve the actual document Node from any Link object that may be proxying it
    * 
    * @return current document Node or document Node resolved from any Link object
    */
   protected Node getLinkResolvedNode()
   {
      Node document = getDocument();
      if (ContentModel.TYPE_FILELINK.equals(document.getType()))
      {
         NodeRef destRef = (NodeRef)document.getProperties().get(ContentModel.PROP_LINK_DESTINATION);
         if (nodeService.exists(destRef))
         {
            document = new Node(destRef);
         }
      }
      return document;
   }
   
   /**
    * Determines whether the current document is versionable
    * 
    * @return true if the document has the versionable aspect
    */
   public boolean isVersionable()
   {
      return getDocument().hasAspect(ContentModel.ASPECT_VERSIONABLE);
   }
   
   /**
    * @return true if the current document has the 'inlineeditable' aspect applied
    */
   public boolean isInlineEditable()
   {
      return getDocument().hasAspect(ContentModel.ASPECT_INLINEEDITABLE);
   }
   
   /**
    * Returns a list of objects representing the versions of the 
    * current document 
    * 
    * @return List of previous versions
    */
   public List getVersionHistory()
   {
      List<MapNode> versions = new ArrayList<MapNode>();
      
      if (getDocument().hasAspect(ContentModel.ASPECT_VERSIONABLE))
      {
         VersionHistory history = this.versionService.getVersionHistory(getDocument().getNodeRef());
   
         if (history != null)
         {
            for (Version version : history.getAllVersions())
            {
               // create a map node representation of the version
               MapNode clientVersion = new MapNode(version.getFrozenStateNodeRef());
               clientVersion.put("versionLabel", version.getVersionLabel());
               clientVersion.put("notes", version.getDescription());
               clientVersion.put("author", version.getCreator());
               clientVersion.put("versionDate", version.getCreatedDate());
               clientVersion.put("url", DownloadContentServlet.generateBrowserURL(version.getFrozenStateNodeRef(), 
                     clientVersion.getName()));
               
               // add the client side version to the list
               versions.add(clientVersion);
            }
         }
      }
      
      return versions;
   }
   
   /**
    * Determines whether the current document has any categories applied
    * 
    * @return true if the document has categories attached
    */
   public boolean isCategorised()
   {
      return getDocument().hasAspect(ContentModel.ASPECT_GEN_CLASSIFIABLE);
   }
   
   /**
    * Returns a list of objects representing the categories applied to the 
    * current document
    *  
    * @return List of categories
    */
   public String getCategoriesOverviewHTML()
   {
      String html = null;
      
      if (isCategorised())
      {
         // we know for now that the general classifiable aspect only will be
         // applied so we can retrive the categories property direclty
         Collection categories = (Collection)this.nodeService.getProperty(getDocument().getNodeRef(), 
               ContentModel.PROP_CATEGORIES);
         
         if (categories == null || categories.size() == 0)
         {
            html = Application.getMessage(FacesContext.getCurrentInstance(), MSG_NO_CATEGORIES_APPLIED);
         }
         else
         {
            StringBuilder builder = new StringBuilder(Application.getMessage(FacesContext.getCurrentInstance(), 
                  MSG_HAS_FOLLOWING_CATEGORIES));
            
            builder.append("<ul>");
            for (Object obj : categories)
            {
               if (obj instanceof NodeRef)
               {
                  if (this.nodeService.exists((NodeRef)obj))
                  {
                     builder.append("<li>");
                     builder.append(Repository.getNameForNode(this.nodeService, (NodeRef)obj));
                     builder.append("</li>");
                  }
               }
            }
            builder.append("</ul>");
            
            html = builder.toString();
         }
      }
      
      return html;
   }

   /**
    * Event handler called to setup the categories for editing
    * 
    * @param event The event
    */
   public void setupCategoriesForEdit(ActionEvent event)
   {
      this.categories = (List)this.nodeService.getProperty(getDocument().getNodeRef(), 
               ContentModel.PROP_CATEGORIES);
   }
   
   /**
    * Returns a Map of the initial categories on the node keyed by the NodeRef
    * 
    * @return Map of initial categories
    */
   public List getCategories()
   {
      return this.categories;
   }
   
   /**
    * Sets the categories Map
    * 
    * @param categories
    */
   public void setCategories(List categories)
   {
      this.categories = categories;
   }
   
   /**
    * Returns the last category added from the multi value editor
    * 
    * @return The last category added
    */
   public NodeRef getAddedCategory()
   {
      return this.addedCategory;
   }

   /**
    * Sets the category added from the multi value editor
    * 
    * @param addedCategory The added category
    */
   public void setAddedCategory(NodeRef addedCategory)
   {
      this.addedCategory = addedCategory;
   }
   
   /**
    * Updates the categories for the current document
    *  
    * @return The outcome
    */
   public String saveCategories()
   {
      String outcome = "cancel";
      
      UserTransaction tx = null;
      
      try
      {
         FacesContext context = FacesContext.getCurrentInstance();
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // firstly retrieve all the properties for the current node
         Map<QName, Serializable> updateProps = this.nodeService.getProperties(
               getDocument().getNodeRef());
         
         // create a node ref representation of the selected id and set the new properties
         updateProps.put(ContentModel.PROP_CATEGORIES, (Serializable)this.categories);
         
         // set the properties on the node
         this.nodeService.setProperties(getDocument().getNodeRef(), updateProps);
         
         // commit the transaction
         tx.commit();
         
         // reset the state of the current document so it reflects the changes just made
         getDocument().reset();
         
         outcome = "finish";
      }
      catch (Throwable e)
      {
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_UPDATE_CATEGORY), e.getMessage()), e);
      }
      
      return outcome;
   }
   
   /**
    * Returns an overview summary of the current state of the attached
    * workflow (if any)
    * 
    * @return Summary HTML
    */
   public String getWorkflowOverviewHTML()
   {
      String html = null;
      
      if (getDocument().hasAspect(ContentModel.ASPECT_SIMPLE_WORKFLOW))
      {
         // get the simple workflow aspect properties
         Map<String, Object> props = getDocument().getProperties();

         String approveStepName = (String)props.get(
               ContentModel.PROP_APPROVE_STEP.toString());
         String rejectStepName = (String)props.get(
               ContentModel.PROP_REJECT_STEP.toString());
         
         Boolean approveMove = (Boolean)props.get(
               ContentModel.PROP_APPROVE_MOVE.toString());
         Boolean rejectMove = (Boolean)props.get(
               ContentModel.PROP_REJECT_MOVE.toString());
         
         NodeRef approveFolder = (NodeRef)props.get(
               ContentModel.PROP_APPROVE_FOLDER.toString());
         NodeRef rejectFolder = (NodeRef)props.get(
               ContentModel.PROP_REJECT_FOLDER.toString());
         
         String approveFolderName = null;
         String rejectFolderName = null;
         
         // get the approve folder name
         if (approveFolder != null)
         {
            Node node = new Node(approveFolder);
            approveFolderName = node.getName();
         }
         
         // get the reject folder name
         if (rejectFolder != null)
         {
            Node node = new Node(rejectFolder);
            rejectFolderName = node.getName();
         }
         
         StringBuilder builder = new StringBuilder();
         
         // calculate the approve action string
         String action = null;
         if (approveMove.booleanValue())
         {
            action = Application.getMessage(FacesContext.getCurrentInstance(), "moved");
         }
         else
         {
            action = Application.getMessage(FacesContext.getCurrentInstance(), "copied");
         }
         
         String docActionPattern = Application.getMessage(FacesContext.getCurrentInstance(), "document_action");
         Object[] params = new Object[] {action, approveFolderName, approveStepName};
         builder.append(MessageFormat.format(docActionPattern, params));
         
         // add details of the reject step if there is one
         if (rejectStepName != null && rejectMove != null && rejectFolderName != null)
         {
            if (rejectMove.booleanValue())
            {
               action = Application.getMessage(FacesContext.getCurrentInstance(), "moved");
            }
            else
            {
               action = Application.getMessage(FacesContext.getCurrentInstance(), "copied");
            }
            
            builder.append("<p>");
            params = new Object[] {action, rejectFolderName, rejectStepName};
            builder.append(MessageFormat.format(docActionPattern, params));
            builder.append("</p>");
         }
         
         html = builder.toString();
      }
         
      return html;
   }
   
   /**
    * Returns the properties for the attached workflow as a map
    * 
    * @return Properties of the attached workflow, null if there is no workflow
    */
   public Map<String, Serializable> getWorkflowProperties()
   {
      if (this.workflowProperties == null && 
          getDocument().hasAspect(ContentModel.ASPECT_SIMPLE_WORKFLOW))
      {
         // get the exisiting properties for the document
         Map<String, Object> props = getDocument().getProperties();
         
         String approveStepName = (String)props.get(
               ContentModel.PROP_APPROVE_STEP.toString());
         String rejectStepName = (String)props.get(
               ContentModel.PROP_REJECT_STEP.toString());
         
         Boolean approveMove = (Boolean)props.get(
               ContentModel.PROP_APPROVE_MOVE.toString());
         Boolean rejectMove = (Boolean)props.get(
               ContentModel.PROP_REJECT_MOVE.toString());
         
         NodeRef approveFolder = (NodeRef)props.get(
               ContentModel.PROP_APPROVE_FOLDER.toString());
         NodeRef rejectFolder = (NodeRef)props.get(
               ContentModel.PROP_REJECT_FOLDER.toString());

         // put the workflow properties in a separate map for use by the JSP
         this.workflowProperties = new HashMap<String, Serializable>(7);
         this.workflowProperties.put(BaseActionWizard.PROP_APPROVE_STEP_NAME, 
               approveStepName);
         this.workflowProperties.put(BaseActionWizard.PROP_APPROVE_ACTION, 
               approveMove ? "move" : "copy");
         this.workflowProperties.put(BaseActionWizard.PROP_APPROVE_FOLDER, approveFolder);
         
         if (rejectStepName == null || rejectMove == null || rejectFolder == null)
         {
            this.workflowProperties.put(BaseActionWizard.PROP_REJECT_STEP_PRESENT, "no");
         }
         else
         {
            this.workflowProperties.put(BaseActionWizard.PROP_REJECT_STEP_PRESENT, 
                  "yes");
            this.workflowProperties.put(BaseActionWizard.PROP_REJECT_STEP_NAME, 
                  rejectStepName);
            this.workflowProperties.put(BaseActionWizard.PROP_REJECT_ACTION, 
                  rejectMove ? "move" : "copy");
            this.workflowProperties.put(BaseActionWizard.PROP_REJECT_FOLDER, 
                  rejectFolder);
         }
      }
      
      return this.workflowProperties;
   }
   
   /**
    * Cancel Workflow Edit dialog
    */
   public String cancelWorkflowEdit()
   {
      // resets the workflow properties map so any changes made
      // don't appear to be persisted
      this.workflowProperties.clear();
      this.workflowProperties = null;
      return "cancel";
   }
   
   /**
    * Saves the details of the workflow stored in workflowProperties
    * to the current document
    *  
    * @return The outcome string
    */
   public String saveWorkflow()
   {
      String outcome = "cancel";
      
      UserTransaction tx = null;
      
      try
      {
         FacesContext context = FacesContext.getCurrentInstance();
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // firstly retrieve all the properties for the current node
         Map<QName, Serializable> updateProps = this.nodeService.getProperties(
               getDocument().getNodeRef());
         
         // update the simple workflow properties
         
         // set the approve step name
         updateProps.put(ContentModel.PROP_APPROVE_STEP,
               this.workflowProperties.get(BaseActionWizard.PROP_APPROVE_STEP_NAME));
         
         // specify whether the approve step will copy or move the content
         boolean approveMove = true;
         String approveAction = (String)this.workflowProperties.get(BaseActionWizard.PROP_APPROVE_ACTION);
         if (approveAction != null && approveAction.equals("copy"))
         {
            approveMove = false;
         }
         updateProps.put(ContentModel.PROP_APPROVE_MOVE, Boolean.valueOf(approveMove));
         
         // create node ref representation of the destination folder
         updateProps.put(ContentModel.PROP_APPROVE_FOLDER,
               this.workflowProperties.get(BaseActionWizard.PROP_APPROVE_FOLDER));
         
         // determine whether there should be a reject step
         boolean requireReject = true;
         String rejectStepPresent = (String)this.workflowProperties.get(
               BaseActionWizard.PROP_REJECT_STEP_PRESENT);
         if (rejectStepPresent != null && rejectStepPresent.equals("no"))
         {
            requireReject = false;
         }
         
         if (requireReject)
         {
            // set the reject step name
            updateProps.put(ContentModel.PROP_REJECT_STEP,
                  this.workflowProperties.get(BaseActionWizard.PROP_REJECT_STEP_NAME));
         
            // specify whether the reject step will copy or move the content
            boolean rejectMove = true;
            String rejectAction = (String)this.workflowProperties.get(
                  BaseActionWizard.PROP_REJECT_ACTION);
            if (rejectAction != null && rejectAction.equals("copy"))
            {
               rejectMove = false;
            }
            updateProps.put(ContentModel.PROP_REJECT_MOVE, Boolean.valueOf(rejectMove));

            // create node ref representation of the destination folder
            updateProps.put(ContentModel.PROP_REJECT_FOLDER,
                  this.workflowProperties.get(BaseActionWizard.PROP_REJECT_FOLDER));
         }
         else
         {
            // set all the reject properties to null to signify there should
            // be no reject step
            updateProps.put(ContentModel.PROP_REJECT_STEP, null);
            updateProps.put(ContentModel.PROP_REJECT_MOVE, null);
            updateProps.put(ContentModel.PROP_REJECT_FOLDER, null);
         }
         
         // set the properties on the node
         this.nodeService.setProperties(getDocument().getNodeRef(), updateProps);
         
         // commit the transaction
         tx.commit();
         
         // reset the state of the current document so it reflects the changes just made
         getDocument().reset();
         
         outcome = "finish";
      }
      catch (Throwable e)
      {
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_UPDATE_SIMPLEWORKFLOW), e.getMessage()), e);
      }
      
      return outcome;
   }
   
   /**
    * Returns the name of the approve step of the attached workflow
    * 
    * @return The name of the approve step or null if there is no workflow
    */
   public String getApproveStepName()
   {
      String approveStepName = null;
      
      if (getDocument().hasAspect(ContentModel.ASPECT_SIMPLE_WORKFLOW))
      {
         approveStepName = (String)getDocument().getProperties().get(
               ContentModel.PROP_APPROVE_STEP.toString());
      }
      
      return approveStepName; 
   }
   
   /**
    * Event handler called to handle the approve step of the simple workflow
    * 
    * @param event The event that was triggered
    */
   public void approve(ActionEvent event)
   {
      UIActionLink link = (UIActionLink)event.getComponent();
      Map<String, String> params = link.getParameterMap();
      String id = params.get("id");
      if (id == null || id.length() == 0)
      {
         throw new AlfrescoRuntimeException("approve called without an id");
      }
      
      NodeRef docNodeRef = new NodeRef(Repository.getStoreRef(), id);
      
      UserTransaction tx = null;
      try
      {
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // call the service to perform the approve
         WorkflowUtil.approve(docNodeRef, this.nodeService, this.copyService);
         
         // commit the transaction
         tx.commit();
         
         // if this was called via the document details dialog we need to reset the document node
         if (getDocument() != null)
         {
            getDocument().reset();
         }
         
         // also make sure the UI will get refreshed
         UIContextService.getInstance(FacesContext.getCurrentInstance()).notifyBeans();
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_WORKFLOW_APPROVE), e.getMessage()), e);
      }
   }
   
   /**
    * Returns the name of the reject step of the attached workflow
    * 
    * @return The name of the reject step or null if there is no workflow
    */
   public String getRejectStepName()
   {
      String approveStepName = null;
      
      if (getDocument().hasAspect(ContentModel.ASPECT_SIMPLE_WORKFLOW))
      {
         approveStepName = (String)getDocument().getProperties().get(
               ContentModel.PROP_REJECT_STEP.toString());
      }
      
      return approveStepName;
   }
   
   /**
    * Event handler called to handle the approve step of the simple workflow
    * 
    * @param event The event that was triggered
    */
   public void reject(ActionEvent event)
   {
      UIActionLink link = (UIActionLink)event.getComponent();
      Map<String, String> params = link.getParameterMap();
      String id = params.get("id");
      if (id == null || id.length() == 0)
      {
         throw new AlfrescoRuntimeException("reject called without an id");
      }
      
      NodeRef docNodeRef = new NodeRef(Repository.getStoreRef(), id);
      
      UserTransaction tx = null;
      try
      {
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // call the service to perform the reject
         WorkflowUtil.reject(docNodeRef, this.nodeService, this.copyService);
         
         // commit the transaction
         tx.commit();
         
         // if this was called via the document details dialog we need to reset the document node
         if (getDocument() != null)
         {
            getDocument().reset();
         }
         
         // also make sure the UI will get refreshed
         UIContextService.getInstance(FacesContext.getCurrentInstance()).notifyBeans();
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_WORKFLOW_REJECT), e.getMessage()), e);
      }
   }
   
   /**
    * Applies the classifiable aspect to the current document
    */
   public void applyClassifiable()
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // add the general classifiable aspect to the node
         this.nodeService.addAspect(getDocument().getNodeRef(), ContentModel.ASPECT_GEN_CLASSIFIABLE, null);
         
         // commit the transaction
         tx.commit();
         
         // reset the state of the current document
         getDocument().reset();
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_ASPECT_CLASSIFY), e.getMessage()), e);
      }
   }
   
   /**
    * Applies the versionable aspect to the current document
    */
   public void applyVersionable()
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // add the versionable aspect to the node
         this.nodeService.addAspect(getDocument().getNodeRef(), ContentModel.ASPECT_VERSIONABLE, null);
         
         // commit the transaction
         tx.commit();
         
         // reset the state of the current document
         getDocument().reset();
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_ASPECT_VERSIONING), e.getMessage()), e);
      }
   }
   
   /**
    * Applies the inlineeditable aspect to the current document
    */
   public String applyInlineEditable()
   {
      UserTransaction tx = null;
      
      try
      {
         tx = Repository.getUserTransaction(FacesContext.getCurrentInstance());
         tx.begin();
         
         // add the inlineeditable aspect to the node
         Map<QName, Serializable> props = new HashMap<QName, Serializable>(1, 1.0f);
         String contentType = null;
         ContentData contentData = (ContentData)getDocument().getProperties().get(ContentModel.PROP_CONTENT);
         if (contentData != null)
         {
            contentType = contentData.getMimetype();
         }
         if (contentType != null)
         {
            // set the property to true by default if the filetype is a known content type
            if (MimetypeMap.MIMETYPE_HTML.equals(contentType) ||
                MimetypeMap.MIMETYPE_TEXT_PLAIN.equals(contentType) ||
                MimetypeMap.MIMETYPE_XML.equals(contentType) ||
                MimetypeMap.MIMETYPE_TEXT_CSS.equals(contentType) ||
                MimetypeMap.MIMETYPE_JAVASCRIPT.equals(contentType))
            {
               props.put(ContentModel.PROP_EDITINLINE, true);
            }
         }
         this.nodeService.addAspect(getDocument().getNodeRef(), ContentModel.ASPECT_INLINEEDITABLE, props);
         
         // commit the transaction
         tx.commit();
         
         // reset the state of the current document
         getDocument().reset();
      }
      catch (Throwable e)
      {
         // rollback the transaction
         try { if (tx != null) {tx.rollback();} } catch (Exception ex) {}
         Utils.addErrorMessage(MessageFormat.format(Application.getMessage(
               FacesContext.getCurrentInstance(), MSG_ERROR_ASPECT_INLINEEDITABLE), e.getMessage()), e);
      }
      
      // force recreation of the details view - this means the properties sheet component will reinit
      return OUTCOME_RETURN;
   }
   
   /**
    * Navigates to next item in the list of content for the current Space
    */
   public void nextItem(ActionEvent event)
   {
      UIActionLink link = (UIActionLink)event.getComponent();
      Map<String, String> params = link.getParameterMap();
      String id = params.get("id");
      if (id != null && id.length() != 0)
      {
         List<Node> nodes = this.browseBean.getContent();
         if (nodes.size() > 1)
         {
            // perform a linear search - this is slow but stateless
            // otherwise we would have to manage state of last selected node
            // this gets very tricky as this bean is instantiated once and never
            // reset - it does not know when the document has changed etc.
            for (int i=0; i<nodes.size(); i++)
            {
               if (id.equals(nodes.get(i).getId()) == true)
               {
                  Node next;
                  // found our item - navigate to next
                  if (i != nodes.size() - 1)
                  {
                     next = nodes.get(i + 1);
                  }
                  else
                  {
                     // handle wrapping case
                     next = nodes.get(0);
                  }
                  
                  // prepare for showing details for this node
                  this.browseBean.setupContentAction(next.getId(), false);
                  break;
               }
            }
         }
      }
   }
   
   /**
    * Navigates to the previous item in the list of content for the current Space
    */
   public void previousItem(ActionEvent event)
   {
      UIActionLink link = (UIActionLink)event.getComponent();
      Map<String, String> params = link.getParameterMap();
      String id = params.get("id");
      if (id != null && id.length() != 0)
      {
         List<Node> nodes = this.browseBean.getContent();
         if (nodes.size() > 1)
         {
            // see above
            for (int i=0; i<nodes.size(); i++)
            {
               if (id.equals(nodes.get(i).getId()) == true)
               {
                  Node previous;
                  // found our item - navigate to previous
                  if (i != 0)
                  {
                     previous = nodes.get(i - 1);
                  }
                  else
                  {
                     // handle wrapping case
                     previous = nodes.get(nodes.size() - 1);
                  }
                  
                  // prepare for showing details for this node
                  this.browseBean.setupContentAction(previous.getId(), false);
                  break;
               }
            }
         }
      }
   }
   
   /**
    * @see org.alfresco.web.bean.BaseDetailsBean#getPropertiesPanelId()
    */
   protected String getPropertiesPanelId()
   {
      return "document-props";
   }

   /**
    * @see org.alfresco.web.bean.BaseDetailsBean#getReturnOutcome()
    */
   protected String getReturnOutcome()
   {
      return OUTCOME_RETURN;
   }
   
   /**
    * Returns a model for use by a template on the Document Details page.
    * 
    * @return model containing current document and current space info.
    */
   public Map getTemplateModel()
   {
      Map<String, Object> model = new HashMap<String, Object>(2, 1.0f);
      
      FacesContext fc = FacesContext.getCurrentInstance();
      TemplateNode documentNode = new TemplateNode(getDocument().getNodeRef(),
              Repository.getServiceRegistry(fc), imageResolver);
      model.put("document", documentNode);
      TemplateNode spaceNode = new TemplateNode(this.navigator.getCurrentNode().getNodeRef(),
              Repository.getServiceRegistry(fc), imageResolver);
      model.put("space", spaceNode);
      
      return model;
   }
   
   /**
    * Returns whether the current document is locked
    * 
    * @return true if the document is checked out
    */
   public boolean isLocked()
   {
      return getDocument().isLocked();
   }
   
   /**
    * Returns whether the current document is a working copy
    * 
    * @return true if the document is a working copy
    */
   public boolean isWorkingCopy()
   {
      return getDocument().hasAspect(ContentModel.ASPECT_WORKING_COPY);
   }
   
   /**
    * @return the working copy document Node for this document if found or null if not
    */
   public Node getWorkingCopyDocument()
   {
      Node workingCopyNode = null;
      
      if (isLocked())
      {
         NodeRef workingCopyRef = this.cociService.getWorkingCopy(getDocument().getNodeRef());
         if (workingCopyRef != null)
         {
            workingCopyNode = new Node(workingCopyRef);
         }
      }
      
      return workingCopyNode;
   }
   
   /**
    * Returns whether the current document is a working copy owned by the current User
    * 
    * @return true if the document is a working copy owner by the current User
    */
   public boolean isOwner()
   {
      return getDocument().isWorkingCopyOwner();
   }
   
   /**
    * Returns the Node this bean is currently representing
    * 
    * @return The Node
    */
   public Node getNode()
   {
      return this.browseBean.getDocument();
   }
   
   /**
    * Returns the document this bean is currently representing
    * 
    * @return The document Node
    */
   public Node getDocument()
   {
      return this.getNode();
   }

   /**
    * Sets the lock service instance the bean should use
    * 
    * @param lockService The LockService
    */
   public void setLockService(LockService lockService)
   {
      this.lockService = lockService;
   }

   /**
    * Sets the version service instance the bean should use
    * 
    * @param versionService The VersionService
    */
   public void setVersionService(VersionService versionService)
   {
      this.versionService = versionService;
   }
   
   /**
    * Sets the copy service instance the bean should use
    * 
    * @param copyService The CopyService
    */
   public void setCopyService(CopyService copyService)
   {
      this.copyService = copyService;
   }
   
   /**
    * Sets the checkincheckout service instance the bean should use
    * 
    * @param cociService The CheckOutCheckInService
    */
   public void setCheckOutCheckInService(CheckOutCheckInService cociService)
   {
      this.cociService = cociService;
   }
}
