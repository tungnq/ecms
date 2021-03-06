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
package org.exoplatform.ecm.webui.component.admin.metadata;

import javax.jcr.nodetype.NodeType;

import org.exoplatform.services.jcr.core.ExtendedPropertyType;
import org.exoplatform.webui.application.WebuiRequestContext;
import org.exoplatform.webui.config.annotation.ComponentConfig;
import org.exoplatform.webui.config.annotation.EventConfig;
import org.exoplatform.webui.core.UIComponent;
import org.exoplatform.webui.core.UIContainer;
import org.exoplatform.webui.event.Event;
import org.exoplatform.webui.event.EventListener;

/**
 * Created by The eXo Platform SARL
 * Author : Dang Van Minh
 *          minh.dang@exoplatform.com
 * Sep 20, 2006
 * 8:59:13 AM
 */
@ComponentConfig(
    template = "app:/groovy/webui/component/admin/metadata/UIMetadataView.gtmpl",
    events = { 
    		@EventConfig(listeners = UIMetadataView.CancelActionListener.class),
        @EventConfig(listeners = UIMetadataView.SelectTabActionListener.class) 
    })
public class UIMetadataView extends UIContainer{

  private NodeType metadataType_  ;
  
  private String selectedTabId = METADATA_VIEW;
  
  static public final String METADATA_VIEW = "Metadata";
  static public final String ELEMENT_VIEW = "Element";

  public String getSelectedTabId()
  {
     return selectedTabId;
  }

  public void setSelectedTab(String renderTabId)
  {
     selectedTabId = renderTabId;
  }

  public void setSelectedTab(int index)
  {
     selectedTabId = ((UIComponent)getChild(index - 1)).getId();
  }

  public UIMetadataView() throws Exception {
  }

  public void  setMetadata(NodeType nodetype) { metadataType_ = nodetype ; }
  public NodeType getMetadata() { return metadataType_ ; }

  public String resolveType(int type) { return ExtendedPropertyType.nameFromValue(type); }

  static public class CancelActionListener extends EventListener<UIMetadataView> {
    public void execute(Event<UIMetadataView> event) throws Exception {
      UIMetadataView uiView = event.getSource() ;
      UIMetadataManager uiManager = uiView.getAncestorOfType(UIMetadataManager.class) ;
      uiManager.removeChildById(UIMetadataManager.VIEW_METADATA_POPUP) ;
      event.getRequestContext().addUIComponentToUpdateByAjax(uiManager) ;
    }
  }
  
  static public class SelectTabActionListener extends EventListener<UIMetadataView>
  {
  	public void execute(Event<UIMetadataView> event) throws Exception
    {
       WebuiRequestContext context = event.getRequestContext();
       String renderTab = context.getRequestParameter(UIComponent.OBJECTID);
       if (renderTab == null)
          return;
       event.getSource().setSelectedTab(renderTab);
       WebuiRequestContext parentContext = (WebuiRequestContext)context.getParentAppRequestContext();
       if (parentContext != null)
       {
          parentContext.setResponseComplete(true);
       }
       else
       {
          context.setResponseComplete(true);
       }
    }
  } 
}
