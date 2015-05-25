package com.onelogin.saml;

import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;

import org.apache.commons.codec.binary.Base64;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.onelogin.AccountSettings;
import com.onelogin.Constants;
import com.onelogin.Error;

public class Response {

	private Document xmlDoc;
	private NodeList assertions;
	private Element rootElement;
	private final AccountSettings accountSettings;
	private final Certificate certificate;
	private String currentUrl;
	private StringBuffer error;

	public Response(AccountSettings accountSettings) throws CertificateException {
		error = new StringBuffer();
		this.accountSettings = accountSettings;		
		certificate = new Certificate();
		certificate.loadCertificate(this.accountSettings.getCertificate());
	}
	
	public Response(AccountSettings accountSettings, String response) throws Exception {
		this(accountSettings);
		loadXmlFromBase64(response);
	}

	public void loadXmlFromBase64(String response) throws Exception {
		Base64 base64 = new Base64();
		byte[] decodedB = base64.decode(response);
		String decodedS = new String(decodedB);
		xmlDoc = Utils.loadXML(decodedS);
		System.out.println("xmlDoc [ "+xmlDoc.getDocumentElement()+" ]");
	}

	// isValid() function should be called to make basic security checks to responses.
	public boolean isValid(){
		try{
			
			// Security Checks
			rootElement = xmlDoc.getDocumentElement();		
			assertions = xmlDoc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");		
			xmlDoc.getDocumentElement().normalize();
			
			// Check SAML version			
			if (!rootElement.getAttribute("Version").equals("2.0")) {
				throw new Exception("Unsupported SAML Version.");
			}
			
			// Check ID in the response	
			if (!rootElement.hasAttribute("ID")) {
				throw new Exception("Missing ID attribute on SAML Response.");
			}
			
			checkStatus();
						
			if (assertions == null || assertions.getLength() != 1) {
				throw new Exception("SAML Response must contain 1 Assertion.");
			}
	
			NodeList nodes = xmlDoc.getElementsByTagNameNS("*", "Signature");
			if (nodes == null || nodes.getLength() == 0) {
				throw new Exception("Can't find signature in Document.");
			}
	
			// Check destination
			String destinationUrl = rootElement.getAttribute("Destination");
			if (destinationUrl != null) {
				if(!destinationUrl.equals(currentUrl)){
					throw new Exception("The response was received at " + currentUrl + " instead of " + destinationUrl);
				}
			}
			
			// Check Audience 
			NodeList nodeAudience = xmlDoc.getElementsByTagNameNS("*", "Audience");
			String audienceUrl = nodeAudience.item(0).getChildNodes().item(0).getNodeValue();
			if (audienceUrl != null) {
				if(!audienceUrl.equals(currentUrl)){
					throw new Exception(audienceUrl + " is not a valid audience for this Response");
				}
			}
			
			// Check SubjectConfirmation, at least one SubjectConfirmation must be valid
			NodeList nodeSubConf = xmlDoc.getElementsByTagNameNS("*", "SubjectConfirmation");
			boolean validSubjectConfirmation = true;
			for(int i = 0; i < nodeSubConf.getLength(); i++){
				Node method = nodeSubConf.item(i).getAttributes().getNamedItem("Method");			
				if(method != null && !method.getNodeValue().equals("urn:oasis:names:tc:SAML:2.0:cm:bearer")){
					continue;
				}
				NodeList childs = nodeSubConf.item(i).getChildNodes();			
				for(int c = 0; c < childs.getLength(); c++){				
					if(childs.item(c).getLocalName().equals("SubjectConfirmationData")){
						Node inResponseTo = childs.item(c).getAttributes().getNamedItem("InResponseTo");					
	//					if(inResponseTo != null && !inResponseTo.getNodeValue().equals("ID of the AuthNRequest")){
	//						validSubjectConfirmation = false;
	//					}
						Node recipient = childs.item(c).getAttributes().getNamedItem("Recipient");					
						if(recipient != null && !recipient.getNodeValue().equals(currentUrl)){
							validSubjectConfirmation = false;
						}
						Node notOnOrAfter = childs.item(c).getAttributes().getNamedItem("NotOnOrAfter");
						if(notOnOrAfter != null){						
							final Calendar notOnOrAfterDate = javax.xml.bind.DatatypeConverter.parseDateTime(notOnOrAfter.getNodeValue());
							Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
							if(notOnOrAfterDate.before(now)){
								validSubjectConfirmation = false;
							}
						}
						Node notBefore = childs.item(c).getAttributes().getNamedItem("NotBefore");
						if(notBefore != null){						
							final Calendar notBeforeDate = javax.xml.bind.DatatypeConverter.parseDateTime(notBefore.getNodeValue());
							Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
							if(notBeforeDate.before(now)){
								validSubjectConfirmation = false;
							}
						}
					}
				}
			}
			if (!validSubjectConfirmation) {
	            throw new Exception("A valid SubjectConfirmation was not found on this Response");
	        }
			
			
	//		if (setIdAttributeExists()) {
	//			tagIdAttributes(xmlDoc);
	//		}
	
			X509Certificate cert = certificate.getX509Cert();		
			DOMValidateContext ctx = new DOMValidateContext(cert.getPublicKey(), nodes.item(0));		
			XMLSignatureFactory sigF = XMLSignatureFactory.getInstance("DOM");		
			XMLSignature xmlSignature = sigF.unmarshalXMLSignature(ctx);		
	
			return xmlSignature.validate(ctx);
		}catch (Error e) {
			error.append(e.getMessage());
			return false;
		}catch(Exception e){
			e.printStackTrace();
		    error.append(e.getMessage());
			return false;
		}
	}

	public String getNameId() throws Exception {
		NodeList nodes = xmlDoc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
		if (nodes.getLength() == 0) {
			throw new Exception("No name id found in Document.");
		}
		return nodes.item(0).getTextContent();
	}

	public String getAttribute(String name) {
		HashMap attributes = getAttributes();
		if (!attributes.isEmpty()) {
			return attributes.get(name).toString();
		}
		return null;
	}

	public HashMap getAttributes() {
		HashMap<String, ArrayList> attributes = new HashMap<String, ArrayList>();
		NodeList nodes = xmlDoc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Attribute");

		if (nodes.getLength() != 0) {
			for (int i = 0; i < nodes.getLength(); i++) {
				NamedNodeMap attrName = nodes.item(i).getAttributes();
				String attName = attrName.getNamedItem("Name").getNodeValue();
				NodeList children = nodes.item(i).getChildNodes();

				ArrayList<String> attrValues = new ArrayList<String>();
				for (int j = 0; j < children.getLength(); j++) {
					attrValues.add(children.item(j).getTextContent());
				}
				attributes.put(attName, attrValues);
			}
		} else {
			return null;
		}
		return attributes;
	}
	
	/**
     * Checks if the Status is success
	 * @throws Exception 
	 * @throws $statusExceptionMsg If status is not success
     */
	public Map<String, String>  checkStatus() throws Exception{
		Map<String, String> status = Utils.getStatus(xmlDoc);
		if(status.containsKey("code") && !status.get("code").equals(Constants.STATUS_SUCCESS) ){
			String statusExceptionMsg = "The status code of the Response was not Success, was " + 
					status.get("code").substring(status.get("code").lastIndexOf(':') + 1);
			if(status.containsKey("msg")){
				statusExceptionMsg += " -> " + status.containsKey("msg");
			}
			throw new Exception(statusExceptionMsg);
		}

		return status;
		
	}

	private boolean setIdAttributeExists() {
		for (Method method : Element.class.getDeclaredMethods()) {
			if (method.getName().equals("setIdAttribute")) {
				return true;
			}
		}
		return false;
	}

	private void tagIdAttributes(Document xmlDoc) {
		throw new UnsupportedOperationException("Not supported yet."); 
	}

	public void setDestinationUrl(String urld){
		currentUrl = urld;
	}
	
	public String getError() {
		if(error!=null)
			return error.toString();
		return "";
	}

	
}