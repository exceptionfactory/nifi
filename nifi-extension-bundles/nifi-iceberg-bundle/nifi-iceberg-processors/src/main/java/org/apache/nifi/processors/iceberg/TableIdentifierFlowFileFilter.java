/*
 * Copyright (c) 2025 Snowflake Computing Inc. All rights reserved.
 */
package org.apache.nifi.processors.iceberg;

import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.FlowFileFilter;
import org.apache.nifi.processor.ProcessContext;

/**
 * Table Identifier FlowFile Filter returns matches based on common Table Identifier
 */
class TableIdentifierFlowFileFilter implements FlowFileFilter {
    // Maximum number of FlowFiles accepted
    private static final int MAXIMUM_FLOW_FILES = 1000;

    private final ProcessContext context;

    private int flowFilesAccepted;

    private long flowFileBytesAccepted;

    private final long maximumBytes;

    private TableIdentifier tableIdentifier;

    TableIdentifierFlowFileFilter(final ProcessContext context, final long maximumBytes) {
        this.context = context;
        this.maximumBytes = maximumBytes;
    }

    @Override
    public FlowFileFilterResult filter(final FlowFile flowFile) {
        final TableIdentifier flowFileTableIdentifier = getFlowFileTableIdentifier(flowFile);
        if (tableIdentifier == null) {
            tableIdentifier = flowFileTableIdentifier;
        }

        final FlowFileFilterResult filterResult;
        if (tableIdentifier.equals(flowFileTableIdentifier)) {
            final long flowFileSize = flowFile.getSize();
            if (flowFileSize >= maximumBytes) {
                // Accept one FlowFile when larger than maximum number of bytes
                filterResult = FlowFileFilterResult.ACCEPT_AND_TERMINATE;
            } else {
                flowFilesAccepted++;
                flowFileBytesAccepted += flowFileSize;

                if (flowFileBytesAccepted >= maximumBytes) {
                    // Reject FlowFile and terminate filtering when exceeding maximum number of bytes
                    filterResult = FlowFileFilterResult.REJECT_AND_TERMINATE;
                } else if (flowFilesAccepted == MAXIMUM_FLOW_FILES) {
                    // Accept FlowFile and terminate filtering when reaching maximum number of FlowFiles
                    filterResult = FlowFileFilterResult.ACCEPT_AND_TERMINATE;
                } else {
                    filterResult = FlowFileFilterResult.ACCEPT_AND_CONTINUE;
                }
            }
        } else {
            filterResult = FlowFileFilterResult.REJECT_AND_CONTINUE;
        }

        return filterResult;
    }

    TableIdentifier getTableIdentifier() {
        return tableIdentifier;
    }

    private TableIdentifier getFlowFileTableIdentifier(final FlowFile flowFile) {
        final String namespace = context.getProperty(PutIcebergRecord.NAMESPACE).evaluateAttributeExpressions(flowFile).getValue();
        final String tableName = context.getProperty(PutIcebergRecord.TABLE_NAME).evaluateAttributeExpressions(flowFile).getValue();
        final Namespace icebergNamespace = Namespace.of(namespace);
        return TableIdentifier.of(icebergNamespace, tableName);
    }
}
