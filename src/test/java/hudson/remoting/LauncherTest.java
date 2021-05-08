package hudson.remoting;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.fail;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

public class LauncherTest {

    @Test
    public void loadDom_Standard() throws IOException, SAXException, ParserConfigurationException {
        // An example of a standard, regular JNLP file.
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/test.jnlp");
        Document document = Launcher.loadDom(jnlpFile);
        Element documentElement = document.getDocumentElement();
        assertThat(documentElement.getNodeName(), is("jnlp"));
        assertThat(documentElement.getChildNodes().getLength(), is(9));
    }

    @Test
    public void loadDom_Lol() throws IOException, SAXException, ParserConfigurationException {
        // A JNLP containing the Billion Laughs DTD
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/lol.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    @Test
    public void loadDom_XxeFile() throws IOException, SAXException, ParserConfigurationException {
        // A JNLP containing an file-type XXE
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/xxe_file.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    @Test
    public void loadDom_XxeHttp() throws IOException, SAXException, ParserConfigurationException {
        // A JNLP containing an http-type XXE
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/xxe_http.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    @Test
    public void loadDom_EmbeddedDoctype() throws IOException, SAXException, ParserConfigurationException {
        // A JNLP containing an embedded doctype
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/embedded_doctype.jnlp");
        shouldFailWithDoctype(jnlpFile);
    }

    private void shouldFailWithDoctype(FileInputStream jnlpFile) throws ParserConfigurationException, SAXException, IOException {
        try {
            Launcher.loadDom(jnlpFile);
            fail("Dom loading should have failed.");
        } catch (SAXParseException spe) {
            assertThat(spe.getMessage(), containsString("DOCTYPE is disallowed"));
        }
    }

}