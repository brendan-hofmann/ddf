/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.security.assertion.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.x500.X500Principal;
import javax.xml.bind.DatatypeConverter;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.lang.StringUtils;
import org.apache.cxf.staxutils.StaxUtils;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.karaf.jaas.boot.principal.RolePrincipal;
import org.apache.wss4j.common.saml.builder.SAML2Constants;
import org.joda.time.DateTime;
import org.opensaml.saml2.core.Attribute;
import org.opensaml.saml2.core.AttributeStatement;
import org.opensaml.saml2.core.AttributeValue;
import org.opensaml.saml2.core.AuthenticatingAuthority;
import org.opensaml.saml2.core.AuthnContext;
import org.opensaml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml2.core.AuthnContextDecl;
import org.opensaml.saml2.core.AuthnContextDeclRef;
import org.opensaml.saml2.core.AuthnStatement;
import org.opensaml.saml2.core.AuthzDecisionStatement;
import org.opensaml.saml2.core.Conditions;
import org.opensaml.saml2.core.EncryptedAttribute;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.SubjectLocality;
import org.opensaml.xml.Namespace;
import org.opensaml.xml.NamespaceManager;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.schema.XSBooleanValue;
import org.opensaml.xml.schema.XSString;
import org.opensaml.xml.util.AttributeMap;
import org.opensaml.xml.util.IDIndex;
import org.opensaml.xml.validation.ValidationException;
import org.opensaml.xml.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import ddf.security.SecurityConstants;
import ddf.security.assertion.SecurityAssertion;
import ddf.security.principal.GuestPrincipal;

/**
 * Implementation of the SecurityAssertion interface. This class wraps a SecurityToken.
 *
 * @author tustisos
 */
public class SecurityAssertionImpl implements SecurityAssertion {

    /**
     * Serial Version UID
     */
    private static final long serialVersionUID = 1L;

    /**
     * Default Hash Value
     */
    private static final int DEFAULT_HASH = 127;

    /**
     * Log4j Logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConstants.SECURITY_LOGGER);

    /**
     * Wrapped SecurityToken.
     */
    private SecurityToken securityToken;

    /**
     * Principal associated with the security token
     */
    private Principal principal;

    private String name;

    private String nameIDFormat;

    /**
     * Attributes associated with the username
     * depending on the value of NameIDFormat
     */
    private ArrayList<String> usernameAttributeList;

    private String issuer;

    private transient List<AttributeStatement> attributeStatements;

    private transient List<AuthnStatement> authenticationStatements;

    private Date notBefore;

    private Date notOnOrAfter;

    /**
     * Uninitialized Constructor
     */
    public SecurityAssertionImpl() {
        init();
    }

    /**
     * Constructor without usernameAttributeList
     *
     * @param securityToken
     */
    public SecurityAssertionImpl(SecurityToken securityToken) {
        this(securityToken, new ArrayList<String>());
    }

    /**
     * Default Constructor
     *
     * @param securityToken         - token to wrap
     * @param usernameAttributeList - configurable list of attributes
     */
    public SecurityAssertionImpl(SecurityToken securityToken, List<String> usernameAttributeList) {
        init();
        this.securityToken = securityToken;
        if (usernameAttributeList == null) {
            this.usernameAttributeList = new ArrayList<>();
        } else {
            this.usernameAttributeList = new ArrayList<>(usernameAttributeList);
        }
        parseToken(securityToken);
        identifyNameIDFormat();
    }

    private void readObject(ObjectInputStream objectInputStream)
            throws IOException, ClassNotFoundException {
        objectInputStream.defaultReadObject();
        init();
    }

    private void init() {
        attributeStatements = new ArrayList<>();
        authenticationStatements = new ArrayList<>();
        usernameAttributeList = new ArrayList<>();
    }

    /**
     * Parses the SecurityToken by wrapping within an AssertionWrapper.
     *
     * @param securityToken SecurityToken
     */
    private void parseToken(SecurityToken securityToken) {
        XMLStreamReader xmlStreamReader = StaxUtils.createXMLStreamReader(securityToken.getToken());

        try {
            AttrStatement attributeStatement = null;
            AuthenticationStatement authenticationStatement = null;
            Attr attribute = null;
            int attrs = 0;
            while (xmlStreamReader.hasNext()) {
                int event = xmlStreamReader.next();
                switch (event) {
                case XMLStreamConstants.START_ELEMENT: {
                    String localName = xmlStreamReader.getLocalName();
                    switch (localName) {
                    case NameID.DEFAULT_ELEMENT_LOCAL_NAME:
                        name = xmlStreamReader.getElementText();
                        for (int i = 0; i < xmlStreamReader.getAttributeCount(); i++) {
                            if (xmlStreamReader.getAttributeLocalName(i)
                                    .equals(NameID.FORMAT_ATTRIB_NAME)) {
                                nameIDFormat = xmlStreamReader.getAttributeValue(i);
                                break;
                            }
                        }
                        break;
                    case AttributeStatement.DEFAULT_ELEMENT_LOCAL_NAME:
                        attributeStatement = new AttrStatement();
                        attributeStatements.add(attributeStatement);
                        break;
                    case AuthnStatement.DEFAULT_ELEMENT_LOCAL_NAME:
                        authenticationStatement = new AuthenticationStatement();
                        authenticationStatements.add(authenticationStatement);
                        attrs = xmlStreamReader.getAttributeCount();
                        for (int i = 0; i < attrs; i++) {
                            String name = xmlStreamReader.getAttributeLocalName(i);
                            String value = xmlStreamReader.getAttributeValue(i);
                            if (AuthnStatement.AUTHN_INSTANT_ATTRIB_NAME.equals(name)) {
                                authenticationStatement.setAuthnInstant(DateTime.parse(value));
                            }
                        }
                        break;
                    case AuthnContextClassRef.DEFAULT_ELEMENT_LOCAL_NAME:
                        if (authenticationStatement != null) {
                            String classValue = xmlStreamReader.getText();
                            classValue = classValue.trim();
                            AuthenticationContextClassRef authenticationContextClassRef = new AuthenticationContextClassRef();
                            authenticationContextClassRef.setAuthnContextClassRef(classValue);
                            AuthenticationContext authenticationContext = new AuthenticationContext();
                            authenticationContext
                                    .setAuthnContextClassRef(authenticationContextClassRef);
                            authenticationStatement.setAuthnContext(authenticationContext);
                        }
                        break;
                    case Attribute.DEFAULT_ELEMENT_LOCAL_NAME:
                        attribute = new Attr();
                        if (attributeStatement != null) {
                            attributeStatement.addAttribute(attribute);
                        }
                        attrs = xmlStreamReader.getAttributeCount();
                        for (int i = 0; i < attrs; i++) {
                            String name = xmlStreamReader.getAttributeLocalName(i);
                            String value = xmlStreamReader.getAttributeValue(i);
                            if (Attribute.NAME_ATTTRIB_NAME.equals(name)) {
                                attribute.setName(value);
                            } else if (Attribute.NAME_FORMAT_ATTRIB_NAME.equals(name)) {
                                attribute.setNameFormat(value);
                            }
                        }
                        break;
                    case AttributeValue.DEFAULT_ELEMENT_LOCAL_NAME:
                        XSString xsString = new XMLString();
                        xsString.setValue(xmlStreamReader.getElementText());
                        if (attribute != null) {
                            attribute.addAttributeValue(xsString);
                        }
                        break;
                    case Issuer.DEFAULT_ELEMENT_LOCAL_NAME:
                        issuer = xmlStreamReader.getElementText();
                        break;
                    case Conditions.DEFAULT_ELEMENT_LOCAL_NAME:
                        attrs = xmlStreamReader.getAttributeCount();
                        for (int i = 0; i < attrs; i++) {
                            String name = xmlStreamReader.getAttributeLocalName(i);
                            String value = xmlStreamReader.getAttributeValue(i);
                            if (Conditions.NOT_BEFORE_ATTRIB_NAME.equals(name)) {
                                notBefore = DatatypeConverter.parseDateTime(value).getTime();
                            } else if (Conditions.NOT_ON_OR_AFTER_ATTRIB_NAME.equals(name)) {
                                notOnOrAfter = DatatypeConverter.parseDateTime(value).getTime();
                            }
                        }
                        break;
                    }
                    break;
                }
                case XMLStreamConstants.END_ELEMENT: {
                    String localName = xmlStreamReader.getLocalName();
                    switch (localName) {
                    case AttributeStatement.DEFAULT_ELEMENT_LOCAL_NAME:
                        attributeStatement = null;
                        break;
                    case Attribute.DEFAULT_ELEMENT_LOCAL_NAME:
                        attribute = null;
                        break;
                    }
                    break;
                }
                }
            }
        } catch (XMLStreamException e) {
            LOGGER.error("Unable to parse security token.", e);
        } finally {
            try {
                xmlStreamReader.close();
            } catch (XMLStreamException ignore) {
                //ignore
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see ddf.security.assertion.SecurityAssertion#getPrincipal()
     */
    @Override
    public Principal getPrincipal() {
        if (securityToken != null) {
            if (principal == null || !principal.getName().equals(name)) {
                String authMethod = null;
                if (authenticationStatements != null) {
                    for (AuthnStatement authnStatement : authenticationStatements) {
                        AuthnContext authnContext = authnStatement.getAuthnContext();
                        if (authnContext != null) {
                            AuthnContextClassRef authnContextClassRef = authnContext
                                    .getAuthnContextClassRef();
                            if (authnContextClassRef != null) {
                                authMethod = authnContextClassRef.getAuthnContextClassRef();
                            }
                        }
                    }
                }
                if (SAML2Constants.AUTH_CONTEXT_CLASS_REF_X509.equals(authMethod) ||
                        SAML2Constants.AUTH_CONTEXT_CLASS_REF_SMARTCARD_PKI.equals(authMethod) ||
                        SAML2Constants.AUTH_CONTEXT_CLASS_REF_SOFTWARE_PKI.equals(authMethod) ||
                        SAML2Constants.AUTH_CONTEXT_CLASS_REF_SPKI.equals(authMethod) ||
                        SAML2Constants.AUTH_CONTEXT_CLASS_REF_TLS_CLIENT.equals(authMethod)) {
                    principal = new X500Principal(name);
                } else if (SAML2Constants.AUTH_CONTEXT_CLASS_REF_KERBEROS.equals(authMethod)) {
                    principal = new KerberosPrincipal(name);
                } else if (principal instanceof GuestPrincipal || name.startsWith(GuestPrincipal.GUEST_NAME_PREFIX)) {
                    principal = new GuestPrincipal(name);
                } else {
                    principal = new AssertionPrincipal(name);
                }
            }
            return principal;
        }
        return null;
    }

    @Override
    public Set<Principal> getPrincipals() {
        Set<Principal> principals = new HashSet<>();
        Principal primary = getPrincipal();
        principals.add(primary);
        principals.add(new RolePrincipal(primary.getName()));
        for (AttributeStatement attributeStatement : getAttributeStatements()) {
            for (Attribute attr : attributeStatement.getAttributes()) {
                if (StringUtils.containsIgnoreCase(attr.getName(), "role")) {
                    for (final XMLObject obj : attr.getAttributeValues()) {
                        principals.add(new RolePrincipal(((XSString) obj).getValue()));
                    }
                }
            }
        }

        return principals;
    }

    /*
     * (non-Javadoc)
     * 
     * @see ddf.security.assertion.SecurityAssertion#getIssuer()
     */
    @Override
    public String getIssuer() {
        return issuer;
    }

    /*
     * (non-Javadoc)
     * 
     * @see ddf.security.assertion.SecurityAssertion#getAttributeStatements()
     */
    @Override
    public List<AttributeStatement> getAttributeStatements() {
        return Collections.unmodifiableList(attributeStatements);
    }

    @Override
    public List<AuthnStatement> getAuthnStatements() {
        return Collections.unmodifiableList(authenticationStatements);
    }

    @Override
    public List<AuthzDecisionStatement> getAuthzDecisionStatements() {
        return new ArrayList<>();
    }

    /*
     * (non-Javadoc)
     * 
     * @see ddf.security.assertion.SecurityAssertion#getSecurityToken()
     */
    @Override
    public SecurityToken getSecurityToken() {
        return securityToken;
    }

    /**
     * Checks if the NameIDFormat is of the following formats below, if not, the name is changed
     * to the value of the first matching usernameAttribute.
     */
    private void identifyNameIDFormat() {
        if (!((StringUtils.containsIgnoreCase(nameIDFormat, SAML2Constants.NAMEID_FORMAT_PERSISTENT)
                || StringUtils
                .containsIgnoreCase(nameIDFormat, SAML2Constants.NAMEID_FORMAT_X509_SUBJECT_NAME)
                || StringUtils
                .containsIgnoreCase(nameIDFormat, SAML2Constants.NAMEID_FORMAT_KERBEROS)
                || StringUtils
                .containsIgnoreCase(nameIDFormat, SAML2Constants.NAMEID_FORMAT_UNSPECIFIED))
                && !name.equals(""))) {
            for (AttributeStatement attributeStatementList : getAttributeStatements()) {
                List<Attribute> attributeList = attributeStatementList.getAttributes();
                for (Attribute attribute : attributeList) {
                    if (listContainsIgnoreCase(usernameAttributeList, attribute.getName())) {
                        name = ((XMLString) attribute.getAttributeValues().get(0)).getValue();
                        return;
                    }
                }
            }
        }
    }

    private boolean listContainsIgnoreCase(List<String> list, String string) {
        for (String next : list) {
            if (next.equalsIgnoreCase(string)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Date getNotBefore() {
        return notBefore;
    }

    @Override
    public Date getNotOnOrAfter() {
        return notOnOrAfter;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("Principal: " + getPrincipal() + ", Attributes: ");
        for (AttributeStatement attributeStatement : getAttributeStatements()) {
            for (Attribute attr : attributeStatement.getAttributes()) {
                result.append("[ ");
                result.append(attr.getName());
                result.append(" : ");
                for (int i = 0; i < attr.getAttributeValues().size(); i++) {
                    result.append(((XSString) attr.getAttributeValues().get(i)).getValue());
                }
                result.append("] ");
            }
        }
        // add this back in when we support parsing this information
        result.append(", AuthnStatements: ");
        for (AuthnStatement authStatement : getAuthnStatements()) {
            result.append("[ ");
            result.append(authStatement.getAuthnInstant() + " : ");
            result.append(authStatement.getAuthnContext().getAuthnContextClassRef()
                    .getAuthnContextClassRef());
            result.append("] ");
        }
        //        result.append(", AuthzDecisionStatements: ");
        //        for (AuthzDecisionStatement authDecision : getAuthzDecisionStatements()) {
        //            result.append("[ ");
        //            result.append(authDecision.getDecision().toString());
        //            result.append(" ]");
        //        }
        return result.toString();
    }

    /**
     * Represents the String values parsed out of the SAML assertion.
     * This class only has the value field implemented for performance reasons.
     */
    private static class XMLString implements XSString {
        private String value;

        protected XMLString() {
        }

        public String getValue() {
            return value;
        }

        public void setValue(String newValue) {
            value = newValue;
        }

        @Override
        public void addNamespace(Namespace namespace) {

        }

        @Override
        public void detach() {

        }

        @Override
        public Element getDOM() {
            return null;
        }

        @Override
        public void setDOM(Element dom) {

        }

        @Override
        public QName getElementQName() {
            return null;
        }

        @Override
        public IDIndex getIDIndex() {
            return null;
        }

        @Override
        public NamespaceManager getNamespaceManager() {
            return null;
        }

        @Override
        public Set<Namespace> getNamespaces() {
            return null;
        }

        @Override
        public String getNoNamespaceSchemaLocation() {
            return null;
        }

        @Override
        public void setNoNamespaceSchemaLocation(String location) {

        }

        public List<XMLObject> getOrderedChildren() {
            return null;
        }

        @Override
        public XMLObject getParent() {
            return null;
        }

        @Override
        public void setParent(XMLObject parent) {

        }

        @Override
        public String getSchemaLocation() {
            return null;
        }

        @Override
        public void setSchemaLocation(String location) {

        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public boolean hasParent() {
            return false;
        }

        @Override
        public void releaseChildrenDOM(boolean propagateRelease) {

        }

        @Override
        public void releaseDOM() {

        }

        @Override
        public void releaseParentDOM(boolean propagateRelease) {

        }

        @Override
        public void removeNamespace(Namespace namespace) {

        }

        @Override
        public XMLObject resolveID(String id) {
            return null;
        }

        @Override
        public XMLObject resolveIDFromRoot(String id) {
            return null;
        }

        @Override
        public Boolean isNil() {
            return false;
        }

        @Override
        public XSBooleanValue isNilXSBoolean() {
            return new XSBooleanValue();
        }

        @Override
        public void setNil(Boolean newNil) {

        }

        @Override
        public void setNil(XSBooleanValue newNil) {

        }

        @Override
        public List<Validator> getValidators() {
            return null;
        }

        @Override
        public void registerValidator(Validator validator) {

        }

        @Override
        public void deregisterValidator(Validator validator) {

        }

        @Override
        public void validate(boolean validateDescendants) throws ValidationException {

        }
    }

    /**
     * This class represents an attribute that has been specified in the SAML assertion.
     * Only the required minimum methods are implemented for performance reasons.
     */
    private static class Attr implements Attribute {

        private String name;

        private String nameFormat;

        private String friendlyName;

        private List<XMLObject> attributeValues = new ArrayList<>();

        protected Attr() {
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getNameFormat() {
            return nameFormat;
        }

        @Override
        public void setNameFormat(String nameFormat) {
            this.nameFormat = nameFormat;
        }

        @Override
        public String getFriendlyName() {
            return friendlyName;
        }

        @Override
        public void setFriendlyName(String friendlyName) {
            this.friendlyName = friendlyName;
        }

        @Override
        public List<XMLObject> getAttributeValues() {
            return Collections.unmodifiableList(attributeValues);
        }

        private void addAttributeValue(XMLObject xmlObject) {
            attributeValues.add(xmlObject);
        }

        @Override
        public AttributeMap getUnknownAttributes() {
            return null;
        }

        @Override
        public void addNamespace(Namespace namespace) {

        }

        @Override
        public void detach() {

        }

        @Override
        public Element getDOM() {
            return null;
        }

        @Override
        public void setDOM(Element dom) {

        }

        @Override
        public QName getElementQName() {
            return null;
        }

        @Override
        public IDIndex getIDIndex() {
            return null;
        }

        @Override
        public NamespaceManager getNamespaceManager() {
            return null;
        }

        @Override
        public Set<Namespace> getNamespaces() {
            return null;
        }

        @Override
        public String getNoNamespaceSchemaLocation() {
            return null;
        }

        @Override
        public void setNoNamespaceSchemaLocation(String location) {

        }

        @Override
        public List<XMLObject> getOrderedChildren() {
            return null;
        }

        @Override
        public XMLObject getParent() {
            return null;
        }

        @Override
        public void setParent(XMLObject parent) {

        }

        @Override
        public String getSchemaLocation() {
            return null;
        }

        @Override
        public void setSchemaLocation(String location) {

        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public boolean hasParent() {
            return false;
        }

        @Override
        public void releaseChildrenDOM(boolean propagateRelease) {

        }

        @Override
        public void releaseDOM() {

        }

        @Override
        public void releaseParentDOM(boolean propagateRelease) {

        }

        @Override
        public void removeNamespace(Namespace namespace) {

        }

        @Override
        public XMLObject resolveID(String id) {
            return null;
        }

        @Override
        public XMLObject resolveIDFromRoot(String id) {
            return null;
        }

        @Override
        public Boolean isNil() {
            return false;
        }

        @Override
        public XSBooleanValue isNilXSBoolean() {
            return new XSBooleanValue();
        }

        @Override
        public void setNil(Boolean newNil) {

        }

        @Override
        public void setNil(XSBooleanValue newNil) {

        }

        @Override
        public List<Validator> getValidators() {
            return null;
        }

        @Override
        public void registerValidator(Validator validator) {

        }

        @Override
        public void deregisterValidator(Validator validator) {

        }

        @Override
        public void validate(boolean validateDescendants) throws ValidationException {

        }
    }

    /**
     * This class represents an attribute statement within a SAML assertion.
     * Only the required minimum methods are implemented for performance reasons.
     */
    private static class AttrStatement implements AttributeStatement {

        private List<Attribute> attributes = new ArrayList<>();

        private List<EncryptedAttribute> encryptedAttributes = new ArrayList<>();

        protected AttrStatement() {
        }

        @Override
        public List<Attribute> getAttributes() {
            return Collections.unmodifiableList(attributes);
        }

        private void addAttribute(Attribute attribute) {
            attributes.add(attribute);
        }

        @Override
        public List<EncryptedAttribute> getEncryptedAttributes() {
            return Collections.unmodifiableList(encryptedAttributes);
        }

        private void addEncryptedAttribute(EncryptedAttribute attribute) {
            encryptedAttributes.add(attribute);
        }

        @Override
        public void addNamespace(Namespace namespace) {

        }

        @Override
        public void detach() {

        }

        @Override
        public Element getDOM() {
            return null;
        }

        @Override
        public void setDOM(Element dom) {

        }

        @Override
        public QName getElementQName() {
            return null;
        }

        @Override
        public IDIndex getIDIndex() {
            return null;
        }

        @Override
        public NamespaceManager getNamespaceManager() {
            return null;
        }

        @Override
        public Set<Namespace> getNamespaces() {
            return null;
        }

        @Override
        public String getNoNamespaceSchemaLocation() {
            return null;
        }

        @Override
        public void setNoNamespaceSchemaLocation(String location) {

        }

        @Override
        public List<XMLObject> getOrderedChildren() {
            return null;
        }

        @Override
        public XMLObject getParent() {
            return null;
        }

        @Override
        public void setParent(XMLObject parent) {

        }

        @Override
        public String getSchemaLocation() {
            return null;
        }

        @Override
        public void setSchemaLocation(String location) {

        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public boolean hasChildren() {
            return attributes.size() > 0;
        }

        @Override
        public boolean hasParent() {
            return false;
        }

        @Override
        public void releaseChildrenDOM(boolean propagateRelease) {

        }

        @Override
        public void releaseDOM() {

        }

        @Override
        public void releaseParentDOM(boolean propagateRelease) {

        }

        @Override
        public void removeNamespace(Namespace namespace) {

        }

        @Override
        public XMLObject resolveID(String id) {
            return null;
        }

        @Override
        public XMLObject resolveIDFromRoot(String id) {
            return null;
        }

        @Override
        public Boolean isNil() {
            return false;
        }

        @Override
        public XSBooleanValue isNilXSBoolean() {
            return new XSBooleanValue();
        }

        @Override
        public void setNil(Boolean newNil) {

        }

        @Override
        public void setNil(XSBooleanValue newNil) {

        }

        @Override
        public List<Validator> getValidators() {
            return null;
        }

        @Override
        public void registerValidator(Validator validator) {

        }

        @Override
        public void deregisterValidator(Validator validator) {

        }

        @Override
        public void validate(boolean validateDescendants) throws ValidationException {

        }
    }

    private static class AuthenticationContextClassRef implements AuthnContextClassRef {

        String authnContextClassRef;

        @Override
        public String getAuthnContextClassRef() {
            return authnContextClassRef;
        }

        @Override
        public void setAuthnContextClassRef(String authnContextClassRef) {
            this.authnContextClassRef = authnContextClassRef;
        }

        @Override
        public List<Validator> getValidators() {
            return null;
        }

        @Override
        public void registerValidator(Validator validator) {

        }

        @Override
        public void deregisterValidator(Validator validator) {

        }

        @Override
        public void validate(boolean validateDescendants) throws ValidationException {

        }

        @Override
        public void addNamespace(Namespace namespace) {

        }

        @Override
        public void detach() {

        }

        @Override
        public Element getDOM() {
            return null;
        }

        @Override
        public void setDOM(Element dom) {

        }

        @Override
        public QName getElementQName() {
            return null;
        }

        @Override
        public IDIndex getIDIndex() {
            return null;
        }

        @Override
        public NamespaceManager getNamespaceManager() {
            return null;
        }

        @Override
        public Set<Namespace> getNamespaces() {
            return null;
        }

        @Override
        public String getNoNamespaceSchemaLocation() {
            return null;
        }

        @Override
        public void setNoNamespaceSchemaLocation(String location) {

        }

        @Override
        public List<XMLObject> getOrderedChildren() {
            return null;
        }

        @Override
        public XMLObject getParent() {
            return null;
        }

        @Override
        public void setParent(XMLObject parent) {

        }

        @Override
        public String getSchemaLocation() {
            return null;
        }

        @Override
        public void setSchemaLocation(String location) {

        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public boolean hasParent() {
            return false;
        }

        @Override
        public void releaseChildrenDOM(boolean propagateRelease) {

        }

        @Override
        public void releaseDOM() {

        }

        @Override
        public void releaseParentDOM(boolean propagateRelease) {

        }

        @Override
        public void removeNamespace(Namespace namespace) {

        }

        @Override
        public XMLObject resolveID(String id) {
            return null;
        }

        @Override
        public XMLObject resolveIDFromRoot(String id) {
            return null;
        }

        @Override
        public Boolean isNil() {
            return null;
        }

        @Override
        public XSBooleanValue isNilXSBoolean() {
            return null;
        }

        @Override
        public void setNil(Boolean newNil) {

        }

        @Override
        public void setNil(XSBooleanValue newNil) {

        }
    }

    private static class AuthenticationContext implements AuthnContext {

        AuthnContextClassRef authnContextClassRef;

        @Override
        public AuthnContextClassRef getAuthnContextClassRef() {
            return authnContextClassRef;
        }

        @Override
        public void setAuthnContextClassRef(AuthnContextClassRef authnContextClassRef) {
            this.authnContextClassRef = authnContextClassRef;
        }

        @Override
        public AuthnContextDecl getAuthContextDecl() {
            return null;
        }

        @Override
        public void setAuthnContextDecl(AuthnContextDecl authnContextDecl) {

        }

        @Override
        public AuthnContextDeclRef getAuthnContextDeclRef() {
            return null;
        }

        @Override
        public void setAuthnContextDeclRef(AuthnContextDeclRef authnContextDeclRef) {

        }

        @Override
        public List<AuthenticatingAuthority> getAuthenticatingAuthorities() {
            return null;
        }

        @Override
        public List<Validator> getValidators() {
            return null;
        }

        @Override
        public void registerValidator(Validator validator) {

        }

        @Override
        public void deregisterValidator(Validator validator) {

        }

        @Override
        public void validate(boolean validateDescendants) throws ValidationException {

        }

        @Override
        public void addNamespace(Namespace namespace) {

        }

        @Override
        public void detach() {

        }

        @Override
        public Element getDOM() {
            return null;
        }

        @Override
        public void setDOM(Element dom) {

        }

        @Override
        public QName getElementQName() {
            return null;
        }

        @Override
        public IDIndex getIDIndex() {
            return null;
        }

        @Override
        public NamespaceManager getNamespaceManager() {
            return null;
        }

        @Override
        public Set<Namespace> getNamespaces() {
            return null;
        }

        @Override
        public String getNoNamespaceSchemaLocation() {
            return null;
        }

        @Override
        public void setNoNamespaceSchemaLocation(String location) {

        }

        @Override
        public List<XMLObject> getOrderedChildren() {
            return null;
        }

        @Override
        public XMLObject getParent() {
            return null;
        }

        @Override
        public void setParent(XMLObject parent) {

        }

        @Override
        public String getSchemaLocation() {
            return null;
        }

        @Override
        public void setSchemaLocation(String location) {

        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public boolean hasParent() {
            return false;
        }

        @Override
        public void releaseChildrenDOM(boolean propagateRelease) {

        }

        @Override
        public void releaseDOM() {

        }

        @Override
        public void releaseParentDOM(boolean propagateRelease) {

        }

        @Override
        public void removeNamespace(Namespace namespace) {

        }

        @Override
        public XMLObject resolveID(String id) {
            return null;
        }

        @Override
        public XMLObject resolveIDFromRoot(String id) {
            return null;
        }

        @Override
        public Boolean isNil() {
            return null;
        }

        @Override
        public XSBooleanValue isNilXSBoolean() {
            return null;
        }

        @Override
        public void setNil(Boolean newNil) {

        }

        @Override
        public void setNil(XSBooleanValue newNil) {

        }
    }

    private static class AuthenticationStatement implements AuthnStatement {

        DateTime authnInstant;

        DateTime sessionNotOnOrAfter;

        AuthnContext authnContext;

        @Override
        public DateTime getAuthnInstant() {
            return authnInstant;
        }

        @Override
        public void setAuthnInstant(DateTime authnInstant) {
            this.authnInstant = authnInstant;
        }

        @Override
        public String getSessionIndex() {
            return null;
        }

        @Override
        public void setSessionIndex(String s) {

        }

        @Override
        public DateTime getSessionNotOnOrAfter() {
            return sessionNotOnOrAfter;
        }

        @Override
        public void setSessionNotOnOrAfter(DateTime sessionNotOnOrAfter) {
            this.sessionNotOnOrAfter = sessionNotOnOrAfter;
        }

        @Override
        public SubjectLocality getSubjectLocality() {
            return null;
        }

        @Override
        public void setSubjectLocality(SubjectLocality subjectLocality) {

        }

        @Override
        public AuthnContext getAuthnContext() {
            return authnContext;
        }

        @Override
        public void setAuthnContext(AuthnContext authnContext) {
            this.authnContext = authnContext;
        }

        @Override
        public List<Validator> getValidators() {
            return null;
        }

        @Override
        public void registerValidator(Validator validator) {

        }

        @Override
        public void deregisterValidator(Validator validator) {

        }

        @Override
        public void validate(boolean validateDescendants) throws ValidationException {

        }

        @Override
        public void addNamespace(Namespace namespace) {

        }

        @Override
        public void detach() {

        }

        @Override
        public Element getDOM() {
            return null;
        }

        @Override
        public void setDOM(Element dom) {

        }

        @Override
        public QName getElementQName() {
            return null;
        }

        @Override
        public IDIndex getIDIndex() {
            return null;
        }

        @Override
        public NamespaceManager getNamespaceManager() {
            return null;
        }

        @Override
        public Set<Namespace> getNamespaces() {
            return null;
        }

        @Override
        public String getNoNamespaceSchemaLocation() {
            return null;
        }

        @Override
        public void setNoNamespaceSchemaLocation(String location) {

        }

        @Override
        public List<XMLObject> getOrderedChildren() {
            return null;
        }

        @Override
        public XMLObject getParent() {
            return null;
        }

        @Override
        public void setParent(XMLObject parent) {

        }

        @Override
        public String getSchemaLocation() {
            return null;
        }

        @Override
        public void setSchemaLocation(String location) {

        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public boolean hasChildren() {
            return false;
        }

        @Override
        public boolean hasParent() {
            return false;
        }

        @Override
        public void releaseChildrenDOM(boolean propagateRelease) {

        }

        @Override
        public void releaseDOM() {

        }

        @Override
        public void releaseParentDOM(boolean propagateRelease) {

        }

        @Override
        public void removeNamespace(Namespace namespace) {

        }

        @Override
        public XMLObject resolveID(String id) {
            return null;
        }

        @Override
        public XMLObject resolveIDFromRoot(String id) {
            return null;
        }

        @Override
        public Boolean isNil() {
            return null;
        }

        @Override
        public XSBooleanValue isNilXSBoolean() {
            return null;
        }

        @Override
        public void setNil(Boolean newNil) {

        }

        @Override
        public void setNil(XSBooleanValue newNil) {

        }
    }

    /**
     * Principal implementation that returns values obtained from the assertion.
     */
    private static class AssertionPrincipal implements Principal, Serializable {
        private String name;

        public AssertionPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object another) {
            if (!(another instanceof Principal)) {
                return false;
            }
            Principal tmpPrin = (Principal) another;
            if (tmpPrin.getName() == null && getName() != null) {
                return false;
            }
            if (tmpPrin.getName() != null && getName() == null) {
                return false;
            }
            if (tmpPrin.getName() == null && getName() == null) {
                return super.equals(another);
            }
            return tmpPrin.getName().equals(getName());
        }

        @Override
        public int hashCode() {
            if (getName() == null) {
                return DEFAULT_HASH;
            }
            return getName().hashCode();
        }

        /**
         * Returns the name of the principal in string format.
         */
        public String toString() {
            return getName();
        }
    }
}
