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
      <td>[Since](CHANGELOG.md)</td>
      <td>[Jenkins version(s)](https://jenkins.io/changelog/)</td>
      <td>Related issues</td>
      <td>Description</td>
    </tr>
    <tr>
      <td>hudson.remoting.ClassFilter.DEFAULTS_OVERRIDE_LOCATION</td>
      <td>null</td>
      <td>2.53.2</td>
      <td>1.639</td>
      <td>[SECURITY-218](https://wiki.jenkins-ci.org/display/SECURITY/Jenkins+Security+Advisory+2015-11-11)</td>
      <td>The path to a file containing alternative regex patterns for remoting class filtering</td>
    </tr>
    <tr>
      <td>hudson.remoting.FlightRecorderInputStream.BUFFER_SIZE</td>
      <td>1048576</td>
      <td>2.41</td>
      <td>1.563</td>
      <td>[JENKINS-22734](https://issues.jenkins-ci.org/browse/JENKINS-22734)</td>
      <td>Size (in bytes) of the flight recorder ring buffer used for debugging remoting issues</td>
    </tr>
    <tr>
      <td>hudson.remoting.Launcher.pingIntervalSec</td>
      <td>0 since 2.60, 600 before</td>
      <td>2.0</td>
      <td>1.367</td>
      <td>[JENKINS-35190](JENKINS-22734](https://issues.jenkins-ci.org/browse/JENKINS-35190)</td>
      <td>Seconds between ping checks to monitor health of slave nodes; 
      0 to disable ping</td>
    </tr>
    <tr>
      <td>hudson.remoting.Launcher.pingTimeoutSec</td>
      <td>240</td>
      <td>2.0</td>
      <td>1.367</td>
      <td>N/A</td>
      <td>If ping of slave node takes longer than this, consider it dead; 
      0 to disable ping</td>
    </tr>
    <tr>
      <td>hudson.remoting.RemoteClassLoader.force</td>
      <td>null</td>
      <td>2.58</td>
      <td>2.4</td>
      <td>[JENKINS-19445](https://issues.jenkins-ci.org/browse/JENKINS-19445) (workaround)</td>
      <td>
      Class name String.
      Forces loading of the specified class name on incoming requests. 
      Works around issues like [JENKINS-19445](https://issues.jenkins-ci.org/browse/JENKINS-19445)</td>
    </tr>
    <tr>
      <td>hudson.remoting.Engine.socketTimeout</td>
      <td>30 minutes</td>
      <td>2.58</td>
      <td>2.4</td>
      <td>[JENKINS-34808](https://issues.jenkins-ci.org/browse/JENKINS-34808)</td>
      <td>Socket read timeout in milliseconds.
      If timeout happens and the <code>failOnSocketTimeoutInReader</code> property is <code>true</code>, the channel will be interrupted.
      </td>
    </tr>
    <tr>
      <td>hudson.remoting.SynchronousCommandTransport.failOnSocketTimeoutInReader</td>
      <td>false</td>
      <td>2.60</td>
      <td>TODO</td>
      <td>[JENKINS-22722](https://issues.jenkins-ci.org/browse/JENKINS-22722)</td>
      <td>Boolean flag.
      Enables the original aggressive behavior, when the channel reader gets interrupted by any 
      <code>SocketTimeoutException</code>
      </td>
    </tr>
    <tr>
      <td>${PROTOCOL_CLASS_NAME}.disabled</td>
      <td>false</td>
      <td>2.59</td>
      <td>2.4</td>
      <td>[JENKINS-34819](https://issues.jenkins-ci.org/browse/JENKINS-34819) </td>
      <td>Boolean flag, which allows disabling particular protocols in remoting.
      Property example: <code>org.jenkinsci.remoting.engine.JnlpProtocol3.disabled</code>
      </td>
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