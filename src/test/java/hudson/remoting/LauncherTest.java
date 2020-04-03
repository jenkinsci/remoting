package hudson.remoting;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.is;

public class LauncherTest {

    @Test
    public void loadDom_Standard() throws IOException, SAXException, ParserConfigurationException {
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/test.jnlp");
        Document document = Launcher.loadDom(jnlpFile);
        Element documentElement = document.getDocumentElement();
        assertThat(documentElement.getNodeName(), is("jnlp"));
        assertThat(documentElement.getChildNodes().getLength(), is(9));
    }

    @Test(expected = SAXParseException.class)
    public void loadDom_Lol() throws IOException, SAXException, ParserConfigurationException {
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/lol.jnlp");
        Document document = Launcher.loadDom(jnlpFile);
        document.getDocumentElement();
    }

    @Test(expected = SAXParseException.class)
    public void loadDom_XxeFile() throws IOException, SAXException, ParserConfigurationException {
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/xxe_file.jnlp");
        Document document = Launcher.loadDom(jnlpFile);
        document.getDocumentElement();
    }

    @Test(expected = SAXParseException.class)
    public void loadDom_XxeHttp() throws IOException, SAXException, ParserConfigurationException {
        FileInputStream jnlpFile = new FileInputStream("src/test/resources/hudson/remoting/xxe_http.jnlp");
        Document document = Launcher.loadDom(jnlpFile);
        document.getDocumentElement();
    }

}