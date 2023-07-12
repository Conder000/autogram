package digital.slovensko.autogram.server.dto;

import java.io.IOException;
import java.io.StringReader;
import java.util.Base64;

import digital.slovensko.autogram.core.AutogramMimeType;
import digital.slovensko.autogram.core.SigningParameters;
import digital.slovensko.autogram.core.XDCTransformer;
import digital.slovensko.autogram.server.errors.RequestValidationException;
import eu.europa.esig.dss.model.InMemoryDocument;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

public class SignRequestBody {
    private final Document document;
    private final ServerSigningParameters parameters;
    private final String payloadMimeType;

    public SignRequestBody(Document document, ServerSigningParameters parameters, String payloadMimeType) {
        this.document = document;
        this.parameters = parameters;
        this.payloadMimeType = payloadMimeType;
    }

    public InMemoryDocument getDocument() throws RequestValidationException {
        if (payloadMimeType == null)
            throw new RequestValidationException("PayloadMimeType is required", "");

        if (document == null)
            throw new RequestValidationException("Document is required", "");

        if (document.getContent() == null)
            throw new RequestValidationException("Document.Content is required", "");

        byte[] content;
        if (isBase64()) {
            content = Base64.getDecoder().decode(document.getContent());
        } else {
            content = document.getContent().getBytes();
        }

        var filename = document.getFilename();
        var mimetype = AutogramMimeType.fromMimeTypeString(payloadMimeType.split(";")[0]);

        return new InMemoryDocument(content, filename, mimetype);
    }

    public SigningParameters getParameters() throws RequestValidationException {
        if (parameters == null)
            throw new RequestValidationException("Parameters are required", "");

        parameters.validate(getDocument().getMimeType());

        SigningParameters signingParameters = parameters.getSigningParameters(isBase64());

        validateXml(signingParameters);

        return signingParameters;
    }

    private boolean isBase64() {
        return payloadMimeType.contains("base64");
    }

    private void validateXml(SigningParameters signingParameters) {
        String xsdSchema = signingParameters.getSchema();
        String xmlContent;

        boolean isXdc = getDocument().getMimeType().equals(AutogramMimeType.XML_DATACONTAINER);
        if (isXdc) {
            XDCTransformer xdcTransformer = XDCTransformer.buildFromSigningParametersAndDocument(signingParameters, getDocument());
            if (signingParameters.getSchema() != null && !xdcTransformer.validateXsdDigest())
                throw new RequestValidationException("XML Datacontainer validation failed", "XSD scheme digest mismatch");

            if (signingParameters.getTransformation() != null && !xdcTransformer.validateXsltDigest())
                throw new RequestValidationException("XML Datacontainer validation failed", "XSLT transformation digest mismatch");

            xmlContent = xdcTransformer.getContentFromXdc();
        } else {
            xmlContent = getDecodedContent();
        }

        if (!validateXmlContentAgainstXsd(xmlContent, xsdSchema)) {
            throw new RequestValidationException("XML validation failed", "XML validation against XSD failed");
        }
    }

    private String getDecodedContent() {
        return isBase64() ? new String(Base64.getDecoder().decode(document.getContent())) : document.getContent();
    }

    private boolean validateXmlContentAgainstXsd(String xmlContent, String xsdSchema) {
        if (xsdSchema == null) {
            return true;
        }
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = factory.newSchema(new StreamSource(new StringReader(xsdSchema)));
            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xmlContent)));
            return true;
        } catch (SAXException | IOException e) {
            return false;
        }
    }
}
