/*
 * Copyright (C) 2003-2007 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 */
package org.exoplatform.services.cms.impl;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.portal.webui.util.Util;
import org.exoplatform.services.cms.BasePath;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.cms.link.LinkManager;
import org.exoplatform.services.cms.templates.TemplateService;
import org.exoplatform.services.cms.thumbnail.ThumbnailPlugin;
import org.exoplatform.services.cms.thumbnail.ThumbnailService;
import org.exoplatform.services.jcr.core.ExtendedNode;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.exoplatform.services.jcr.impl.core.NodeImpl;
import org.exoplatform.services.jcr.util.Text;
import org.exoplatform.services.jcr.util.VersionHistoryImporter;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityRegistry;
import org.exoplatform.services.security.MembershipEntry;
import org.exoplatform.services.wcm.core.NodetypeConstant;
import org.exoplatform.services.wcm.utils.WCMCoreUtils;

import com.ibm.icu.text.Transliterator;

/**
 * @author benjaminmestrallet
 */
public class Utils {
  private final static Log   LOG          = ExoLogger.getLogger(Utils.class.getName());

  private static final String ILLEGAL_SEARCH_CHARACTERS= "\\!^()+{}[]:\"-";

  public static final String MAPPING_FILE = "mapping.properties";

  public static final String EXO_SYMLINK = "exo:symlink";
  
  public static final long KB = 1024L;
  public static final long MB = 1024L*KB;
  public static final long GB = 1024L*MB;

  public static Node makePath(Node rootNode, String path, String nodetype)
  throws PathNotFoundException, RepositoryException {
    return makePath(rootNode, path, nodetype, null);
  }

  @SuppressWarnings("unchecked")
  public static Node makePath(Node rootNode, String path, String nodetype, Map permissions)
  throws PathNotFoundException, RepositoryException {
    String[] tokens = path.split("/") ;
    Node node = rootNode;
    for (int i = 0; i < tokens.length; i++) {
      String token = tokens[i];
      if(token.length() > 0) {
        if(node.hasNode(token)) {
          node = node.getNode(token) ;
        } else {
          node = node.addNode(token, nodetype);
          node.getSession().save();
          node = (Node)node.getSession().getItem(node.getPath());
          if (node.canAddMixin("exo:privilegeable")){
            node.addMixin("exo:privilegeable");
          }
          if(permissions != null){
            ((ExtendedNode)node).setPermissions(permissions);
          }
        }
      }
    }
    rootNode.save();
    return node;
  }

  /**
   * this function used to process import version history for a node
   *
   * @param currentNode
   * @param versionHistorySourceStream
   * @param mapHistoryValue
   * @throws Exception
   */
  public static void processImportHistory(Node currentNode,
                                          InputStream versionHistorySourceStream,
                                          Map<String, String> mapHistoryValue) throws Exception {
    //read stream, get the version history data & keep it inside a map
    Map<String, byte[]> mapVersionHistoryData = getVersionHistoryData (versionHistorySourceStream);

    //import one by one
    for (String uuid : mapHistoryValue.keySet()) {
      for (String name : mapVersionHistoryData.keySet()) {
        if (name.equals(uuid + ".xml")) {
          try {
            byte[] versionHistoryData = mapVersionHistoryData.get(name);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(versionHistoryData);
            String value = mapHistoryValue.get(uuid);
            Node versionableNode = currentNode.getSession().getNodeByUUID(uuid);
            importHistory((NodeImpl) versionableNode,
                          inputStream,
                          getBaseVersionUUID(value),
                          getPredecessors(value),
                          getVersionHistory(value));
            currentNode.getSession().save();
            break;
          } catch (ItemNotFoundException item) {
            currentNode.getSession().refresh(false);
            if (LOG.isErrorEnabled()) {
              LOG.error("Can not found versionable node" + item, item);
            }
          } catch (Exception e) {
            currentNode.getSession().refresh(false);
            if (LOG.isErrorEnabled()) {
              LOG.error("Import version history failed " + e, e);
            }
          }
        }
      }
    }
  }

  /**
   * This function is used to get the version history data which is kept inside the xml files
   * @param versionHistorySourceStream
   * @return a map saving version history data with format: [file name, version history data]
   * @throws IOException
   */
  private static Map<String, byte[]> getVersionHistoryData (InputStream versionHistorySourceStream) throws IOException {
    Map<String, byte[]> mapVersionHistoryData = new HashMap<String, byte[]>();
    ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(versionHistorySourceStream));
    byte[] data = new byte[1024];
    ZipEntry entry = zipInputStream.getNextEntry();
    while (entry != null) {
      //get binary data inside the zip entry
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      int available = -1;
      while ((available = zipInputStream.read(data, 0, 1024)) > -1) {
        out.write(data, 0, available);
      }

      //save data into map
      mapVersionHistoryData.put(entry.getName(), out.toByteArray());

      //go to next entry
      out.close();
      zipInputStream.closeEntry();
      entry = zipInputStream.getNextEntry();
    }

    zipInputStream.close();
    return mapVersionHistoryData;
  }

  /**
   * do import a version into a node
   *
   * @param versionableNode
   * @param versionHistoryStream
   * @param baseVersionUuid
   * @param predecessors
   * @param versionHistory
   * @throws RepositoryException
   * @throws IOException
   */
  private static void importHistory(NodeImpl versionableNode,
                                    InputStream versionHistoryStream,
                                    String baseVersionUuid,
                                    String[] predecessors,
                                    String versionHistory) throws RepositoryException, IOException {
    VersionHistoryImporter versionHistoryImporter = new VersionHistoryImporter(versionableNode,
                                                                               versionHistoryStream,
                                                                               baseVersionUuid,
                                                                               predecessors,
                                                                               versionHistory);
    versionHistoryImporter.doImport();
  }

  /**
   * get data from the version history file
   *
   * @param importHistorySourceStream
   * @return
   * @throws Exception
   */
  public static Map<String, String> getMapImportHistory(InputStream importHistorySourceStream) throws Exception {
    ZipInputStream zipInputStream = new ZipInputStream(importHistorySourceStream);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] data = new byte[1024];
    ZipEntry entry = zipInputStream.getNextEntry();
    Map<String, String> mapHistoryValue = new HashMap<String, String>();
    while (entry != null) {
      int available = -1;
      if (entry.getName().equals(MAPPING_FILE)) {
        while ((available = zipInputStream.read(data, 0, 1024)) > -1) {
          out.write(data, 0, available);
        }
        InputStream inputStream = new ByteArrayInputStream(out.toByteArray());
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        String strLine;
        // Read File Line By Line
        while ((strLine = br.readLine()) != null) {
          // Put the history information into list
          if (strLine.indexOf("=") > -1) {
            mapHistoryValue.put(strLine.split("=")[0], strLine.split("=")[1]);
          }
        }
        // Close the input stream
        inputStream.close();
        zipInputStream.closeEntry();
        break;
      }
      entry = zipInputStream.getNextEntry();
    }
    out.close();
    zipInputStream.close();
    return mapHistoryValue;
  }

  private static String getBaseVersionUUID(String valueHistory) {
    String[] arrHistoryValue = valueHistory.split(";");
    return arrHistoryValue[1];
  }

  private static String[] getPredecessors(String valueHistory) {
    String[] arrHistoryValue = valueHistory.split(";");
    String strPredecessors = arrHistoryValue[1];
    if (strPredecessors.indexOf(",") > -1) {
      return strPredecessors.split(",");
    }
    return new String[] { strPredecessors };
  }

  private static String getVersionHistory(String valueHistory) {
    String[] arrHistoryValue = valueHistory.split(";");
    return arrHistoryValue[0];
  }

  public static String getPersonalDrivePath(String parameterizedDrivePath, String userId) throws Exception {
    SessionProvider sessionProvider = WCMCoreUtils.getUserSessionProvider();
    NodeHierarchyCreator nodeHierarchyCreator = WCMCoreUtils.getService(NodeHierarchyCreator.class);
    Node userNode = nodeHierarchyCreator.getUserNode(sessionProvider, userId);
    return StringUtils.replaceOnce(parameterizedDrivePath,
                                   nodeHierarchyCreator.getJcrPath(BasePath.CMS_USERS_PATH) + "/${userId}",
                                   userNode.getPath());
  }

  public static List<PropertyDefinition> getProperties(Node node) throws Exception {
    List<PropertyDefinition> properties = new ArrayList<PropertyDefinition>();
    NodeType nodetype = node.getPrimaryNodeType() ;
    Collection<NodeType> types = new ArrayList<NodeType>() ;
    types.add(nodetype) ;
    NodeType[] mixins = node.getMixinNodeTypes() ;
    if (mixins != null) types.addAll(Arrays.asList(mixins)) ;
    for(NodeType nodeType : types) {
        for(PropertyDefinition property : nodeType.getPropertyDefinitions()) {
          String name = property.getName();
          if(!name.equals("exo:internalUse")&& !property.isProtected()&& !node.hasProperty(name)) {
            properties.add(property);
          }
        }
    }
    return properties;
  }

  public static boolean isInTrash(Node node) throws RepositoryException {
    TrashService trashService = WCMCoreUtils.getService(TrashService.class);
    return trashService.isInTrash(node);
  }

  /**
   * Gets the title.
   *
   * @param node the node
   * @return the title
   * @throws Exception the exception
   */
  public static String getTitle(Node node) throws Exception {
    String title = null;
    if (node.hasProperty("exo:title")) {
      title = node.getProperty("exo:title").getValue().getString();
    } else if (node.hasNode("jcr:content")) {
      Node content = node.getNode("jcr:content");
      if (content.hasProperty("dc:title")) {
        try {
          title = content.getProperty("dc:title").getValues()[0].getString();
        } catch (Exception ex) {
          title = null;
        }
      }
    }
    if (title == null) {
      if (node.isNodeType("nt:frozenNode")) {
        String uuid = node.getProperty("jcr:frozenUuid").getString();
        Node originalNode = node.getSession().getNodeByUUID(uuid);
        title = originalNode.getName();
      } else {
        title = node.getName();
      }

    }
    return StringEscapeUtils.escapeHtml(Text.unescapeIllegalJcrChars(title));
  }

  public static String escapeIllegalCharacterInQuery(String query) {
    String ret = query;
    if(ret != null) {
      for (char c : ILLEGAL_SEARCH_CHARACTERS.toCharArray()) {
        ret = ret.replace(c + "", "\\" + c);
      }
    }
    return ret;
  }
  /**
   * 
   * @param     : node
   * @param     : keepInTrash true if the link will be move to trash, otherwise set by false
   * @throws    : Exception
   * @Objective : Remove all the link of a deleted node
   * @Author    : Nguyen The Vinh from ECM of eXoPlatform
   *              vinh.nguyen@exoplatform.com
   */
  public static void removeDeadSymlinks(Node node, boolean keepInTrash) throws Exception {
    if (isInTrash(node)) {
      return;
    }
    LinkManager linkManager = WCMCoreUtils.getService(LinkManager.class);
    TrashService trashService = WCMCoreUtils.getService(TrashService.class);
    SessionProvider sessionProvider = SessionProvider.createSystemProvider();
    Queue<Node> queue = new LinkedList<Node>();
    queue.add(node);

    try {
      while (!queue.isEmpty()) {
        node = queue.poll();
        if (!node.isNodeType(EXO_SYMLINK)) {
          try {
            List<Node> symlinks = linkManager.getAllLinks(node, EXO_SYMLINK);

            // Before removing symlinks, We order symlinks by name descending, index descending.
            // Example: symlink[3],symlink[2], symlink[1] to avoid the case that
            // the index of same name symlink automatically changed to increasing one by one
            Collections.sort(symlinks, new Comparator<Node>()
              {
                @Override
                public int compare(Node node1, Node node2) {
                  try {
                    String name1 = node1.getName();
                    String name2 = node2.getName();
                    if (name1.equals(name2)) {
                      int index1 = node1.getIndex();
                      int index2 = node2.getIndex();
                      return -1 * ((Integer)index1).compareTo(index2);
                    }
                    return -1 * name1.compareTo(name2);
                  } catch (RepositoryException e) {
                    return 0;
                  }
                }
              });

            for (Node symlink : symlinks) {
              synchronized (symlink) {
                if (keepInTrash) {
                  trashService.moveToTrash(symlink, sessionProvider, 1);
                }else {
                  symlink.remove();
                }
              }
            }
          } catch (Exception e) {
            if (LOG.isWarnEnabled()) {
              LOG.warn(e.getMessage());
            }
          }
            for (NodeIterator iter = node.getNodes(); iter.hasNext(); ) {
              queue.add(iter.nextNode());
            }
        }
      }
    } catch (Exception e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(e.getMessage());
      }
    } finally {
      sessionProvider.close();
    }
  }
  public static void removeDeadSymlinks(Node node) throws Exception {
    removeDeadSymlinks(node, true);
  }

  public static Node getChildOfType(Node node, String childType) throws Exception {
    if (node == null) {
      return null;
    }
    NodeIterator iter = node.getNodes();
    while (iter.hasNext()) {
      Node child = iter.nextNode();
      if (child.isNodeType(childType)) {
        return child;
      }
    }
    return null;
  }

  public static boolean hasChild(Node node, String childType) throws Exception {
    return (getChildOfType(node, childType) != null);
  }

  /**
   * Get Service Log Content Node of specific service.
   *
   * @param serviceName
   * @return
   * @throws Exception
   */
  public static Node getServiceLogContentNode(String serviceName, String logType) throws Exception {
    // Get workspace and session where store service log
    ManageableRepository repository = WCMCoreUtils.getRepository();
    Session session = WCMCoreUtils.getSystemSessionProvider().getSession(repository.getConfiguration().getDefaultWorkspaceName(), repository);
    Node serviceLogContentNode = null;

    if (session.getRootNode().hasNode("exo:services")) {
      // Get service folder
      Node  serviceFolder = session.getRootNode().getNode("exo:services");

      // Get service node
      Node serviceNode = serviceFolder.hasNode(serviceName) ?
        serviceFolder.getNode(serviceName) : serviceFolder.addNode(serviceName, NodetypeConstant.NT_UNSTRUCTURED);

      // Get log node of service
      String serviceLogName =  serviceName + "_" + logType;
      Node serviceLogNode = serviceNode.hasNode(serviceLogName) ?
        serviceNode.getNode(serviceLogName) : serviceNode.addNode(serviceLogName, NodetypeConstant.NT_FILE);

      // Get service log content
      if (serviceLogNode.hasNode(NodetypeConstant.JCR_CONTENT)) {
        serviceLogContentNode = serviceLogNode.getNode(NodetypeConstant.JCR_CONTENT);
      } else {
        serviceLogContentNode = serviceLogNode.addNode(NodetypeConstant.JCR_CONTENT, NodetypeConstant.NT_RESOURCE);
        serviceLogContentNode.setProperty(NodetypeConstant.JCR_ENCODING, "UTF-8");
        serviceLogContentNode.setProperty(NodetypeConstant.JCR_MIME_TYPE, MediaType.TEXT_PLAIN);
        serviceLogContentNode.setProperty(NodetypeConstant.JCR_DATA, StringUtils.EMPTY);
        serviceLogContentNode.setProperty(NodetypeConstant.JCR_LAST_MODIFIED, new Date().getTime());
      }
    }
    session.save();
    return serviceLogContentNode;
  }

  public static String getObjectId(String nodePath) throws UnsupportedEncodingException {
    return URLEncoder.encode(nodePath.replaceAll("'", "\\\\'"), "utf-8");
  }

  /**
   * Clean string.
   *
   * @param str the str
   *
   * @return the string
   */
  public static String cleanString(String str) {
    Transliterator accentsconverter = Transliterator.getInstance("Latin; NFD; [:Nonspacing Mark:] Remove; NFC;");
    str = accentsconverter.transliterate(str);
    //the character ? seems to not be changed to d by the transliterate function
    StringBuffer cleanedStr = new StringBuffer(str.trim());
    // delete special character
    for(int i = 0; i < cleanedStr.length(); i++) {
      char c = cleanedStr.charAt(i);
      if(c == ' ') {
        if (i > 0 && cleanedStr.charAt(i - 1) == '-') {
          cleanedStr.deleteCharAt(i--);
        } else {
          c = '-';
          cleanedStr.setCharAt(i, c);
        }
        continue;
      }
      if(i > 0 && !(Character.isLetterOrDigit(c) || c == '-')) {
        cleanedStr.deleteCharAt(i--);
        continue;
      }
      if(i > 0 && c == '-' && cleanedStr.charAt(i-1) == '-')
        cleanedStr.deleteCharAt(i--);
    }
    while (StringUtils.isNotEmpty(cleanedStr.toString()) && !Character.isLetterOrDigit(cleanedStr.charAt(0))) {
      cleanedStr.deleteCharAt(0);
    }
    String clean = cleanedStr.toString().toLowerCase();
    if (clean.endsWith("-")) {
      clean = clean.substring(0, clean.length()-1);
    }

    return clean;
  }

  public static List<String> getMemberships() throws Exception {
    String userId = ConversationState.getCurrent().getIdentity().getUserId();
    List<String> userMemberships = new ArrayList<String>();
   userMemberships.add(userId);
    // here we must retrieve memberships of the user using the
    // IdentityRegistry Service instead of Organization Service to
    // allow JAAS based authorization
    Collection<MembershipEntry> memberships = getUserMembershipsFromIdentityRegistry(userId);
    if (memberships != null) {
      for (MembershipEntry membership : memberships) {
        String role = membership.getMembershipType() + ":" + membership.getGroup();
        userMemberships.add(role);
      }
   }
   return userMemberships;
  }

  /**
   * this method retrieves memberships of the user having the given id using the
   * IdentityRegistry service instead of the Organization service to allow JAAS
   * based authorization
   *
   * @param authenticatedUser the authenticated user id
   * @return a collection of MembershipEntry
   */
  private static Collection<MembershipEntry> getUserMembershipsFromIdentityRegistry(String authenticatedUser) {
    IdentityRegistry identityRegistry = WCMCoreUtils.getService(IdentityRegistry.class);
    Identity currentUserIdentity = identityRegistry.getIdentity(authenticatedUser);
    return currentUserIdentity.getMemberships();
  }
  
  public static String getNodeTypeIcon(Node node, String appended, String mode)
  throws RepositoryException {
    StringBuilder str = new StringBuilder();
    if (node == null)
      return "";
    
    // Primary node type
    String nodeType = node.getPrimaryNodeType().getName();
    
    // Get real node if node is symlink
    if (node.isNodeType(EXO_SYMLINK)) {
      LinkManager linkManager = Util.getUIPortal().getApplicationComponent(
          LinkManager.class);
      try {
        nodeType = node.getProperty(NodetypeConstant.EXO_PRIMARYTYPE).getString();
        node = linkManager.getTarget(node);
        if (node == null)
          return "";
      } catch (Exception e) {
        return "";
      }
    }
    
    if (node.isNodeType(NodetypeConstant.EXO_TRASH_FOLDER)) {
      nodeType = NodetypeConstant.EXO_TRASH_FOLDER;
    }
    else if (node.isNodeType(NodetypeConstant.EXO_FAVOURITE_FOLDER)) {
      nodeType = NodetypeConstant.EXO_FAVOURITE_FOLDER;
    }
    else if (nodeType.equals(NodetypeConstant.NT_UNSTRUCTURED) || nodeType.equals(NodetypeConstant.NT_FOLDER)) {
      for (String specificFolder : NodetypeConstant.SPECIFIC_FOLDERS) {
        if (node.isNodeType(specificFolder)) {
          nodeType = specificFolder;
          break;
        }
      }
    }

    nodeType = nodeType.replace(':', '_');

    // Default css class
    String defaultCssClass;
    if (node.isNodeType(NodetypeConstant.NT_UNSTRUCTURED) || node.isNodeType(NodetypeConstant.NT_FOLDER)) {
      defaultCssClass = "Folder";
    } else if (node.isNodeType(NodetypeConstant.NT_FILE)) {
      defaultCssClass = "File";
    } else {
      defaultCssClass = nodeType;
    }
    defaultCssClass += "Default";
    
    str.append(appended);
    str.append(defaultCssClass);
    str.append(" ");
    str.append(appended);
    str.append(nodeType);
    if (mode != null && mode.equalsIgnoreCase("Collapse"))
      str.append(' ').append(mode).append(appended).append(nodeType);
    if (node.isNodeType(NodetypeConstant.NT_FILE)) {
      if (node.hasNode(NodetypeConstant.JCR_CONTENT)) {
        Node jcrContentNode = node.getNode(NodetypeConstant.JCR_CONTENT);
        str.append(' ').append(appended).append(
            jcrContentNode.getProperty(NodetypeConstant.JCR_MIMETYPE).getString().toLowerCase().replaceAll(
                "/|\\.", ""));
      }
    }
    return str.toString();
  }
    
  public static String getNodeTypeIcon(Node node, String appended)
    throws RepositoryException {
    return getNodeTypeIcon(node, appended, null);
  }
  
  /**
   * Check if a node is document type.
   * @param node
   * @return true: is document; false: not document
   * @throws Exception
   */
  public static boolean isDocument(Node node) throws Exception {
    TemplateService templateService = WCMCoreUtils.getService(TemplateService.class);
    if (templateService==null) return false;
    List<String> documentTypeList = templateService.getDocumentTemplates();
    if (documentTypeList==null) return false;
    for (String documentType : documentTypeList) {
      if (node.getPrimaryNodeType().isNodeType(documentType)) {
        return true;
      }
    }
    return false;
  }
  
  /**
   * gets the file size in friendly format
   * @param node the file node
   * @return the file size
   * @throws Exception
   */
  public static String fileSize(Node node) throws Exception {
    if (node == null || !node.isNodeType("nt:file")) {
      return "";
    }
    StringBuffer ret = new StringBuffer();
    ret.append(" - ");
    long size = 0;
    try {
      size = node.getProperty("jcr:content/jcr:data").getLength();
    } catch (Exception e) {
      LOG.error("Can not get file size", e);
    }
    long byteSize = size % KB;
    long kbSize = (size % MB) / KB;
    long mbSize = (size % GB) / MB;
    long gbSize = size / GB;
    
    if (gbSize >= 1) {
      ret.append(gbSize).append(refine(mbSize)).append(" GB");
    } else if (mbSize >= 1) {
      ret.append(mbSize).append(refine(kbSize)).append(" MB");
    } else if (kbSize > 1) {
      ret.append(kbSize).append(refine(byteSize)).append(" KB");
    } else {
      ret.append("1 KB");
    }
    return ret.toString();
  }
  
  public static boolean isSupportThumbnailView(String mimeType) {
    List<String> thumbnailMimeTypes = new ArrayList<String>();
    List<ComponentPlugin> componentPlugins = WCMCoreUtils.getService(ThumbnailService.class).getComponentPlugins();
    for (ComponentPlugin plugin : componentPlugins) {
      if (plugin instanceof ThumbnailPlugin) {
        thumbnailMimeTypes.addAll(((ThumbnailPlugin) plugin).getMimeTypes());
      }
    }
    return thumbnailMimeTypes.contains(mimeType);
  }
  
  /**
   * refines the size up to 3 digits, add '0' in front if necessary.
   * @param size the size
   * @return the size in 3 digit format
   */
  private static String refine(long size) {
    if (size == 0) {
      return "";
    }
    String strSize = String.valueOf(size);
    while (strSize.length() < 3) {
      strSize = "0" + strSize;
    }
    return "," + Math.round(Double.valueOf(Integer.valueOf(strSize) / 100.0));
  }
}
