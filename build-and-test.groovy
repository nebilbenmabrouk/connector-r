/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Integration test for the PARConnector with Scheduler 3.4.0
// JAVA_HOME must be at least a jdk 1.7
// R_HOME must be at least R 3.0.2
// 
// MainTest class:
// - creates a Context that fires a check and build of the PARConnector package
// - creates a TestSetup that wraps the tests with scheduler start/stop
// - creates a junit test case for each r script which name starts with 'test' in 'tests' dir
// 
// Context class:
// - builds the PARConnector
// - copies the contents to the scheduler's home addons directory
// - starts the rm+scheduler+4nodes using the standard start-server.js script
//
// TestRScript class:
// - behaves as a single method junit test case
// - runs a r script and checks the exit value
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.DefaultLogger;

class MainTest extends TestCase {

	static Test suite() {

		def ctx = new Context()
		ctx.check();
        ctx.buildParscript();
		ctx.build();

		def ts = new TestSuite();

        def testSetup = new TestSetup(ts) {
            protected void setUp(  ) throws Exception {
                ctx.startScheduler()
            }

            protected void tearDown(  ) throws Exception {
            	ctx.stopScheduler()
            }
        };

        ctx.testsDir.listFiles().each {
			def t = new TestRscript()
			t.ctx = ctx
			t.rTestFile = it
			if ( it.getName().startsWith("test") ) {
				ts.addTest(t);
			}
		}

		return testSetup
    }
}

class TestRscript extends TestCase {
	def ctx
	def rTestFile

	void testRScript(){
		println '\n######################\n#   RUNNING integration test ' + rTestFile + ' ... \n######################'			
		def proc = [ctx.rExe, '--quiet' ,'--vanilla', '--args','QUITONERROR', '<', rTestFile].execute(ctx.newEnv, ctx.parConnectorDir)
		proc.out << rTestFile.getText()
		proc.out.close()
		proc.waitForProcessOutput(System.out, System.err)
		assert proc.waitFor() == 0 : 'It seems integration test failed'
	}

	String getName() {
		return rTestFile.getName()
	}

	TestRscript(){ super('testRScript') }
}

class Context {	
	def isWindows = System.properties['os.name'].toLowerCase().contains('windows')
	def isMac = System.getProperty("os.name").toLowerCase().contains('mac')
	def isLinux = System.getProperty("os.name").toLowerCase().contains('nux')
	def schedHome = System.getenv()['SCHEDULER_HOME']
	def rHome = System.getenv()['R_HOME']
    def jreHome
	def jdkHome

	def rExe, homeDir, distDir, parConnectorDir, parscriptDir, testsDir, rLibraryDir, rLibraryPath
	def newEnv = []
	def schedProcess

	void check(){
		assert schedHome != null : '!!! Unable to locate Scheduler home dir, the SCHEDULER_HOME env var is undefined !!!'
		assert rHome != null : '!!! Unable to locate R home dir, the R_HOME env var is undefined !!!'

		// Locate r binary
		def fs = File.separator
		if (isWindows) {
			def arch = System.getenv()['ProgramFiles(x86)'] != null ? 'x64' : 'i386'
			rExe = rHome+fs+'bin'+fs+arch+fs+'R.exe'
			rLibraryDir = new File(rHome,'library')
		} else { // for isLinux and isMac
			rExe = rHome+fs+'bin'+fs+'R'			
			rLibraryDir = new File(System.getenv()['R_LIBS'])
			assert rLibraryDir.exists() : '!!! Unable to locate R library, please set R_LIBS env var !!!'
		}
		rLibraryPath = rLibraryDir.getAbsolutePath()
		// todo check for rJava	

		// Check that the current dir is 'r' or its parent
		def cd = new File(System.getProperty('user.dir'));
		homeDir = cd;
		distDir = new File(homeDir, 'dist');
		parConnectorDir = new File(homeDir, 'PARConnector');
        parscriptDir =  new File(homeDir, 'parscript');
		testsDir = new File(parConnectorDir,'functionalTests')

		assert parConnectorDir.exists() : '!!! Unable to locate PARConnector dir !!!'
        assert parscriptDir.exists() : '!!! Unable to locate parscript dir !!!'
        assert testsDir.exists() : '!!! Unable to locate functionalTests dir !!!'

		if (isWindows || isLinux) {
            def javaHomeF = new File(System.getenv()['JAVA_HOME'])

            assert !javaHomeF.getName().equals("jre") : "!!! JAVA_HOME must contain a path to a JDK !!!"
            jdkHome = javaHomeF.getAbsolutePath();
            jreHome = new File(javaHomeF, "jre").getAbsolutePath();

			assert (new File(jreHome)).exists() : "!!! Unable to locate the jre !!!"

            // ! THIS IS A FIX FOR rJava that requires JAVA_HOME to be the location of the JRE !
            System.getenv().each() {k,v ->
                if ('JAVA_HOME'.equals(k)) { v = jreHome }
                newEnv << k+'='+v
            }
			if (isLinux) {
				// LD_LIBRARY_PATH=/home/jenkins/shared/java/x86_64/sun/jdk1.7.0_45/jre/lib/amd64:/home/jenkins/shared/java/x86_64/sun/jdk1.7.0_45/jre/lib/amd64/server/		
				def libDir = new File(jreHome, 'lib')
				assert libDir.exists() : "!!! Unable to locate the lib dir inside the jre dir !!!"

				def so64Dir = new File(libDir, 'amd64')
				if (so64Dir.exists()) { // 64 bits jre
					newEnv << 'LD_LIBRARY_PATH='+so64Dir+File.pathSeparator+(new File(so64Dir, 'server'))
				} 
				
				def so32Dir = new File(libDir, 'i386')
				if (so32Dir.exists()){ // 32 bits jre
					newEnv << 'LD_LIBRARY_PATH='+so32Dir+File.pathSeparator+(new File(so32Dir, 'client'))
				}
			}
		}
		if (isMac) {
			// By default it seems the apple's java6 installation is a mixed jre and jdk
			def jreHome = System.getenv()['JAVA_HOME']
			System.getenv().each() {k,v ->
				newEnv << k+'='+v
			}
		}
	}

    void buildParscript() {
        println '\n######################\n#       Building Parscript and Runnning tests ... \n######################'
        def antFile = new File(parscriptDir,"build.xml")
        def project = new Project()
        def consoleLogger = new DefaultLogger()
        consoleLogger.errorPrintStream = System.err
        consoleLogger.outputPrintStream = System.out
        consoleLogger.messageOutputLevel = Project.MSG_INFO
        project.addBuildListener(consoleLogger);
        project.init()
        ProjectHelper.projectHelper.parse(project, antFile)
        project.executeTarget("clean")
        project.executeTarget("test")

        println '\n######################\n#   COPYING parscript + deps to scheduler addons & PARConnector ... \n######################'
        new AntBuilder().copy(todir: parConnectorDir.getPath() + '/inst/java/') {
            fileset(dir: parscriptDir.getPath() + '/dist/') {
                include(name: "*.jar")
            }
        }
        // copy to scheduler addons dir is done already by parscript
    }

	void build(){
		println '\n######################\n#   CHECKING R packages from package sources ... \n######################'
		(new File(homeDir, 'PARConnector.Rcheck')).deleteDir()
		assert run([rExe, 'CMD', 'check', '--no-codoc', '--no-manual', '--no-multiarch', 'PARConnector'], newEnv, homeDir
			).waitFor() == 0 : 'It seems R CMD check failed'
	    
		distDir.deleteDir()
		distDir.mkdir()

		println '\n######################\n#   BUILDING PARConnector ... \n######################'
		assert  run([rExe, 'CMD', 'INSTALL','--build','--preclean', '--with-keep.source','--no-multiarch', parConnectorDir.getAbsolutePath()], newEnv, distDir
			).waitFor() == 0 : 'It seems R CMD INSTALL --build failed'

		assert distDir.listFiles().length > 0 : 'The dist dir is empty'
		def archiveFile = distDir.listFiles().first();
		assert (archiveFile.getName().endsWith('.tar.gz') || (archiveFile.getName().endsWith('.tgz')) || archiveFile.getName().endsWith('.zip')) : 'It seems the archive was not build correctly'
	
		println '\n######################\n#   REMOVING previous PARConnector ... \n######################'
		run([rExe, 'CMD', 'REMOVE', '--library='+rLibraryPath, 'PARConnector'], newEnv, homeDir
			).waitFor();

		println '\n######################\n#   INSTALLING PARConnector ... \n######################'
		assert run([rExe, 'CMD', 'INSTALL', '--no-multiarch', '--library='+rLibraryPath, archiveFile.getAbsolutePath()], newEnv, homeDir
			).waitFor() == 0 : 'It seems R CMD install failed'


		println '\n######################\n#   CLEANING scheduler .logs and DB dirs ... \n######################'
		(new File(schedHome, '.logs')).deleteDir()
		(new File(schedHome, 'SCHEDULER_DB')).deleteDir()
		(new File(schedHome, 'RM_DB')).deleteDir()
	}

	void startScheduler() {
		println '\n######################\n#   STARTING the Scheduler using start-server.js ... \n######################'
		schedProcess = ["jrunscript", "start-server.js"].execute(null, new File(schedHome, 'bin'))

		try {
			schedProcess.inputStream.eachLine {
				println '>> ' + it
				if (it.contains('terminate all')) {
					throw new Exception()
				}
			}
		} catch (e) {}

		try {
			int value = schedProcess.exitValue();
			if (value != 0) {
				throw new Exception("Could not start the Scheduler, start-server.js exited with code " + value);
			}
		} catch (IllegalThreadStateException e) {/* the process is still running */}
	}

	void stopScheduler() {
		println '\n######################\n#   SHUTTING down the Scheduler ... \n######################'    
		try {
			schedProcess.out << 'exit\n';
			schedProcess.out.close();
		} catch (e) {e.printStackTrace()}
		schedProcess.waitFor();	
	}


	// Executes a command and redirects stdout/stderr
	def run = {command, env, dir ->
		println 'Running ' + command.join(" ")
		def proc = command.execute(env, dir)
		proc.waitForProcessOutput(System.out, System.err)
		return proc
	}
}

import org.codehaus.groovy.tools.FileSystemCompiler
import org.codehaus.groovy.control.CompilerConfiguration

def scriptPath = getClass().protectionDomain.codeSource.location.path
def currentDir = new File(scriptPath).parent

def binDir = new File(currentDir, 'bin')
binDir.deleteDir()
binDir.mkdir()

def reportDir = new File(currentDir, 'report')
reportDir.deleteDir()
reportDir.mkdir()

CompilerConfiguration config = new CompilerConfiguration()
config.setTargetDirectory(binDir)

def compiler = new FileSystemCompiler(config);
compiler.compile(scriptPath)

def ant = new AntBuilder()
//ant.project.getBuildListeners().firstElement().setMessageOutputLevel(3)
ant.junit (haltonfailure: 'yes', showoutput: 'yes'){
	classpath(){
		pathelement( location: binDir )
	}
    batchtest(todir: reportDir, fork:'no'){
    	fileset( dir: binDir, includes: 'MainTest.class')
    	formatter( type: "plain", usefile:'false')
    	formatter( type: "xml", usefile:'true')
    }
}