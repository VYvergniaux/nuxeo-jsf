/*
 * (C) Copyright 2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 */
package org.nuxeo.ecm.webapp.directory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.faces.context.FacesContext;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.ui.web.directory.DirectoryHelper;
import org.nuxeo.runtime.api.Framework;

/**
 * A vocabulary tree node based on l10nvocabulary or l10nxvocabulary directory.
 * These schemas store translations in columns of the form label_xx_XX or
 * label_xx. The label of a node is retrieved from column label_xx_XX (where
 * xx_XX is the current locale name) if it exists, from column label_xx (where
 * xx is the current locale language) else. If this one doesn't exist either,
 * the english label (from label_en) is used.
 *
 * @since 5.5
 * @author <a href="mailto:qlamerand@nuxeo.com">Quentin Lamerand</a>
 */
public class VocabularyTreeNode {

    private static final Log log = LogFactory.getLog(VocabularyTreeNode.class);

    public static final String PARENT_FIELD_ID = "parent";

    public static final String LABEL_FIELD_PREFIX = "label_";

    public static final String DEFAULT_LANGUAGE = "en";

    public static final String OBSOLETE_FIELD = "obsolete";

    protected final String path;

    protected final int level;

    protected String id;

    protected String label;

    protected DirectoryService directoryService;

    protected List<VocabularyTreeNode> children;

    protected String vocabularyName;

    protected DocumentModelList childrenEntries;

    protected boolean displayObsoleteEntries;

    protected String orderingField;

    protected Comparable orderingValue;

    protected char keySeparator;

    public VocabularyTreeNode(int level, String id, String description,
            String path, String vocabularyName,
            DirectoryService directoryService) {
        this(level, id, description, path, vocabularyName, directoryService,
                false, '/', null);
    }

    public VocabularyTreeNode(int level, String id, String description,
            String path, String vocabularyName,
            DirectoryService directoryService, boolean displayObsoleteEntries,
            char keySeparator, String orderingField) {
        this(level, id, description, path, vocabularyName, directoryService,
                displayObsoleteEntries, keySeparator, orderingField, null);
    }

    public VocabularyTreeNode(int level, String id, String description,
            String path, String vocabularyName,
            DirectoryService directoryService, boolean displayObsoleteEntries,
            char keySeparator, String orderingField, Comparable orderingValue) {
        this.level = level;
        this.id = id;
        this.label = description;
        this.path = path;
        this.vocabularyName = vocabularyName;
        this.directoryService = directoryService;
        this.displayObsoleteEntries = displayObsoleteEntries;
        this.keySeparator = keySeparator;
        this.orderingField = orderingField;
        this.orderingValue = orderingValue;
    }

    public List<VocabularyTreeNode> getChildren() {
        if (children != null) {
            return children;
        }
        children = new ArrayList<VocabularyTreeNode>();
        try {
            String schemaName = getDirectorySchema();
            DocumentModelList results = getChildrenEntries();
            Locale locale = FacesContext.getCurrentInstance().getViewRoot().getLocale();
            for (DocumentModel result : results) {
                String childIdendifier = result.getId();
                String childLabel = computeLabel(locale, result, schemaName);
                String childPath;
                if ("".equals(path)) {
                    childPath = childIdendifier;
                } else {
                    childPath = path + keySeparator + childIdendifier;
                }
                Comparable orderingValue = null;
                if (!StringUtils.isBlank(orderingField)) {
                    orderingValue = (Comparable) result.getProperty(schemaName,
                            orderingField);
                }
                children.add(new VocabularyTreeNode(level + 1, childIdendifier,
                        childLabel, childPath, vocabularyName,
                        getDirectoryService(), displayObsoleteEntries,
                        keySeparator, orderingField, orderingValue));
            }

            // sort children
            Comparator<? super VocabularyTreeNode> cmp;
            if (StringUtils.isBlank(orderingField)
                    || "label".equals(orderingField)) {
                cmp = new LabelComparator(); // sort alphabetically
            } else {
                cmp = new OrderingComparator();
            }
            Collections.sort(children, cmp);

            return children;
        } catch (ClientException e) {
            log.error(e);
            return children;
        }
    }

    public static String computeLabel(Locale locale, DocumentModel entry,
            String schemaName) {
        String fieldName = LABEL_FIELD_PREFIX + locale.toString();
        String label = null;
        try {
            label = (String) entry.getProperty(schemaName, fieldName);
        } catch (Exception e) {
        }
        if (label == null) {
            fieldName = LABEL_FIELD_PREFIX + locale.getLanguage();
            try {
                label = (String) entry.getProperty(schemaName, fieldName);
            } catch (Exception e) {
            }
        }
        if (label == null) {
            fieldName = LABEL_FIELD_PREFIX + DEFAULT_LANGUAGE;
            try {
                label = (String) entry.getProperty(schemaName, fieldName);
            } catch (Exception e) {
            }
        }
        return label;
    }

    private class LabelComparator implements Comparator<VocabularyTreeNode> {
        @Override
        public int compare(VocabularyTreeNode o1, VocabularyTreeNode o2) {
            return ObjectUtils.compare(o1.getLabel(), o2.getLabel());
        }
    }

    private class OrderingComparator implements Comparator<VocabularyTreeNode> {
        @Override
        public int compare(VocabularyTreeNode o1, VocabularyTreeNode o2) {
            if (o1.getOrdering() == null && o2.getOrdering() != null) {
                return -1;
            } else if (o1.getOrdering() != null && o2.getOrdering() == null) {
                return 1;
            } else if (o1.getOrdering() == o2.getOrdering()) {
                return 0;
            } else {
                return o1.getOrdering().compareTo(o2.getOrdering());
            }
        }
    }

    protected DocumentModelList getChildrenEntries() throws ClientException {
        if (childrenEntries != null) {
            // memorized directory lookup since directory content is not
            // suppose to change
            // XXX: use the cache manager instead of field caching strategy
            return childrenEntries;
        }
        Session session = getDirectorySession();
        try {
            Map<String, Serializable> filter = new HashMap<String, Serializable>();

            String directorySchema = getDirectorySchema();
            SchemaManager schemaManager = Framework.getLocalService(SchemaManager.class);
            Schema schema = schemaManager.getSchema(directorySchema);
            if (schema == null) {
                throw new DirectoryException(directorySchema
                        + " is not a registered directory");
            }
            if (level == 0 && schema.hasField(PARENT_FIELD_ID)) {
                // filter on empty parent
                filter.put(PARENT_FIELD_ID, "");
            } else {
                String[] bitsOfPath = StringUtils.split(path, keySeparator);
                filter.put(PARENT_FIELD_ID, bitsOfPath[level - 1]);
            }

            if (!displayObsoleteEntries) {
                filter.put(OBSOLETE_FIELD, Long.valueOf(0));
            }

            if (filter.isEmpty()) {
                childrenEntries = session.getEntries();
            } else {
                childrenEntries = session.query(filter);
            }
            return childrenEntries;
        } finally {
            session.close();
        }
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getPath() {
        return path;
    }

    public Comparable getOrdering() {
        return orderingValue;
    }

    protected DirectoryService getDirectoryService() {
        if (directoryService == null) {
            directoryService = DirectoryHelper.getDirectoryService();
        }
        return directoryService;
    }

    protected String getDirectorySchema() throws ClientException {
        return getDirectoryService().getDirectorySchema(vocabularyName);
    }

    protected Session getDirectorySession() throws ClientException {
        return getDirectoryService().open(vocabularyName);
    }

}
