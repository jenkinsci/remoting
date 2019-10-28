Remoting Configuration
====

Remoting can be configured via System properties. 
These properties require independent configuration on both sides of the channel.

### Available settings

<table>
  <tbody>
    <tr>
      <td>System property</td>
      <td>Default value</td>
      <td><a href="../CHANGELOG.md">Since</a></td>
      <td><a href="https://jenkins.io/changelog/">Jenkins version(s)</a></td>
      <td>Related issues</td>
      <td>Description</td>
    </tr>
    <tr>
      <td>hudson.remoting.ClassFilter.DEFAULTS_OVERRIDE_LOCATION</td>
      <td>null</td>
      <td>2.53.2</td>
      <td>1.639</td>
      <td><a href="https://wiki.jenkins-ci.org/display/SECURITY/Jenkins+Security+Advisory+2015-11-11">SECURITY-218</a></td>
      <td>The path to a file containing alternative regex patterns for remoting class filtering</td>
    </tr>
    <tr>
      <td>hudson.remoting.FlightRecorderInputStream.BUFFER_SIZE</td>
      <td>1048576</td>
      <td>2.41</td>
      <td>1.563</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-22734">JENKINS-22734</a></td>
      <td>Size (in bytes) of the flight recorder ring buffer used for debugging remoting issues</td>
    </tr>
    <tr>
      <td>hudson.remoting.Launcher.pingIntervalSec</td>
      <td>0 since 2.60, 600 before</td>
      <td>2.0</td>
      <td>1.367</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-35190">JENKINS-35190</a></td>
      <td>Seconds between ping checks to monitor health of agent nodes; 
      0 to disable ping</td>
    </tr>
    <tr>
      <td>hudson.remoting.Launcher.pingTimeoutSec</td>
      <td>240</td>
      <td>2.0</td>
      <td>1.367</td>
      <td>N/A</td>
      <td>If ping of agent node takes longer than this, consider it dead; 
      0 to disable ping</td>
    </tr>
    <tr>
      <td>hudson.remoting.RemoteClassLoader.force</td>
      <td>null</td>
      <td>2.58</td>
      <td>2.4</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-19445">JENKINS-19445</a> (workaround)</td>
      <td>
      Class name String.
      Forces loading of the specified class name on incoming requests. 
      Works around issues like <a href="https://issues.jenkins-ci.org/browse/JENKINS-19445">JENKINS-19445</a></td>
    </tr>
    <tr>
      <td>hudson.remoting.Engine.socketTimeout</td>
      <td>30 minutes</td>
      <td>2.58</td>
      <td>2.4</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-34808">JENKINS-34808</a></td>
      <td>Socket read timeout in milliseconds.
      If timeout happens and the <code>failOnSocketTimeoutInReader</code> property is <code>true</code>, the channel will be interrupted.
      </td>
    </tr>
    <tr>
      <td>hudson.remoting.SynchronousCommandTransport.failOnSocketTimeoutInReader</td>
      <td>false</td>
      <td>2.60</td>
      <td>TODO</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-22722">JENKINS-22722</a></td>
      <td>Boolean flag.
      Enables the original aggressive behavior, when the channel reader gets interrupted by any 
      <code>SocketTimeoutException</code>
      </td>
    </tr>
    <tr>
      <td>hudson.remoting.ExportTable.unexportLogSize</td>
      <td>1024</td>
      <td>2.40</td>
      <td>?</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-20707">JENKINS-20707</a></td>
      <td>Defines number of entries to be stored in the unexport history, which is being analyzed during the invalid object ID analysis.</td>
    </tr>
    <tr>
      <td>${PROTOCOL_FULLY_QUALIFIED_NAME}.disabled, 
      where PROTOCOL_FULLY_QUALIFIED_NAME equals 
      <code>PROTOCOL_HANDLER_CLASSNAME</code> without the <code>Handler</code> suffix.</td>
      <td>false</td>
      <td>2.59</td>
      <td>2.4</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-34819">JENKINS-34819</a></td>
      <td>Boolean flag, which allows disabling particular protocols in remoting. 
      <br/><br/>
      Property example: <code>org.jenkinsci.remoting.engine.JnlpProtocol3.disabled</code>
      </td>
    </tr>
    <tr>
      <td>org.jenkinsci.remoting.nio.NioChannelHub.disabled</td>
      <td>false</td>
      <td>2.62.3</td>
      <td>TODO</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-39290">JENKINS-39290</a></td>
      <td>Boolean flag to disable NIO-based socket connection handling, and switch back to classic IO. Used to isolate the problem.</td>
    </tr>
    <tr>
      <td>org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver.protocolNamesToTry</td>
      <td>false</td>
      <td>TODO</td>    
      <td>TODO</td>
      <td><a href="https://issues.jenkins-ci.org/browse/JENKINS-41730">JENKINS-41730</a></td>
      <td>If specified, only the protocols from the list will be tried during the connection. The option provides protocol names, but the order of the check is defined internally and cannot be changed.</td>
    </tr>
    <tr>
        <td><a href="no_proxy.md">NO_PROXY</a> (or no_proxy)</td>
      <td></td>
      <td></td>
      <td></td>
      <td></td>
      <td>Provides specifications for hosts that should not be proxied. See the <a href="no_proxy.md">NO_PROXY Environment Variable</a> page for details on supported specifications.</td>
    </tr>
    <!--Template
    <tr>
      <td></td>
      <td></td>
      <td></td>
      <td></td>
      <td></td>
      <td></td>
    </tr>-->
  <tbody>
</table>