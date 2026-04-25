/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.authorization;

import org.apache.nifi.authorization.resource.Authorizable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizeConfigVerificationTest {

    @Mock
    private Authorizer authorizer;

    @Mock
    private AuthorizableLookup authorizableLookup;

    @Mock
    private ComponentAuthorizable componentAuthorizable;

    @Mock
    private Authorizable componentAuthorizableDelegate;

    @Mock
    private Authorizable ancestor;

    @Test
    void testAuthorizeComponentWriteApproved() {
        when(componentAuthorizable.getAuthorizable()).thenReturn(componentAuthorizableDelegate);

        AuthorizeConfigVerification.authorize(authorizer, authorizableLookup, componentAuthorizable, Map.of());

        verify(componentAuthorizableDelegate).authorize(eq(authorizer), eq(RequestAction.WRITE), any());
        verify(ancestor, never()).authorize(any(), any(), any());
    }

    @Test
    void testAuthorizeComponentAndAncestorWriteApproved() {
        when(componentAuthorizable.getAuthorizable()).thenReturn(componentAuthorizableDelegate);

        AuthorizeConfigVerification.authorize(authorizer, authorizableLookup, componentAuthorizable, Map.of(), ancestor);

        verify(ancestor).authorize(eq(authorizer), eq(RequestAction.WRITE), any());
        verify(componentAuthorizableDelegate).authorize(eq(authorizer), eq(RequestAction.WRITE), any());
    }

    @Test
    void testAuthorizeNullAncestorSkipped() {
        when(componentAuthorizable.getAuthorizable()).thenReturn(componentAuthorizableDelegate);

        AuthorizeConfigVerification.authorize(authorizer, authorizableLookup, componentAuthorizable, Map.of(), null);

        verify(componentAuthorizableDelegate).authorize(eq(authorizer), eq(RequestAction.WRITE), any());
        verify(ancestor, never()).authorize(any(), any(), any());
    }

    @Test
    void testAuthorizeComponentWriteDenied() {
        when(componentAuthorizable.getAuthorizable()).thenReturn(componentAuthorizableDelegate);
        doThrow(new AccessDeniedException("denied")).when(componentAuthorizableDelegate).authorize(eq(authorizer), eq(RequestAction.WRITE), any());

        assertThrows(AccessDeniedException.class, () -> AuthorizeConfigVerification.authorize(authorizer, authorizableLookup, componentAuthorizable, Map.of()));
    }

    @Test
    void testAuthorizeAncestorWriteDenied() {
        doThrow(new AccessDeniedException("denied")).when(ancestor).authorize(eq(authorizer), eq(RequestAction.WRITE), any());

        assertThrows(AccessDeniedException.class, () -> AuthorizeConfigVerification.authorize(authorizer, authorizableLookup, componentAuthorizable, Map.of(), ancestor));

        verify(componentAuthorizable, never()).getAuthorizable();
    }
}
