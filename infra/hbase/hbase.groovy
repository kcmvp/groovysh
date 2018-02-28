#! /usr/bin/env groovy
@Grapes([
        @Grab(group = 'com.google.guava', module = 'guava', version = '18.0')
])
import com.google.common.base.CaseFormat
import groovy.io.FileType
import groovy.xml.MarkupBuilder

def currentPath = new File(getClass().protectionDomain.codeSource.location.path).parent
GroovyShell groovyShell = new GroovyShell()
def shell = groovyShell.parse(new File(currentPath, "../../core/Shell.groovy"))
def logback = groovyShell.parse(new File(currentPath, "../../core/Logback.groovy"))
def osBuilder = groovyShell.parse(new File(currentPath, "../os/osBuilder.groovy"))
def logger = logback.getLogger("infra-hbase")

def configFile = new File('hbaseCfg.groovy')
def config = null
if (configFile.exists()) {
    config = new ConfigSlurper().parse(configFile.text)
}



def buildOs = { onRemote ->
    logger.info("** Check and set /etc/hosts for all servers ...")
    osBuilder.etcHost(config.setting.hosts, onRemote)
}

def cfg = {
    def tmpDir = File.createTempDir()

    logger.info("** Generate configurations ...")
    def generate = new File("hbase")
    generate.mkdir()


    ["hbaseSite"].each { prop ->

        def fileName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, prop)

        logger.info "** Generate ${fileName}.xml ..."
        def file = new File(generate, "${fileName}.xml");
        def writer = new FileWriter(file)
        def xml = new MarkupBuilder(writer)

        xml.mkp.xmlDeclaration([version: '1.0', encoding: 'UTF-8'])
        xml.mkp.pi("xml-stylesheet": [type: "text/xsl", href: "configuration.xsl"])

        xml.configuration {
            config.get(prop).flatten().each { sec ->
                property {
                    name(sec.key)
                    value(sec.value)
                }

            }
        }
        writer.close()
    }

    logger.info("** Generate regionservers ...")
    println config.regionservers
    def regionservers = new File(generate, "regionservers").withWriter { w ->
        def bw = new BufferedWriter(w)
        config.regionservers.split(",").each { h ->
            bw.write(h)
            bw.newLine()
        }
        bw.close()
    }
    logger.info("** Configurations are generated at {}", generate.absolutePath)
}

def deploy = { deployable, host ->

    if (config.setting.hosts.contains(host)) {

        def rootName = deployable.name.replace(".tar", "").replace(".gz", "").replace(".tgz", "");
        logger.info("** unzipping ${deployable.absolutePath} at ${tmpDir.absolutePath} ......")
        def rt = shell.exec("tar -vxf ${deployable.absolutePath} -C ${tmpDir.absolutePath}")
        if (!rt.code) {
            generate.eachFileRecurse(FileType.FILES) { f ->
                if (!f.name.equalsIgnoreCase("folder")) {
                    logger.info("** copy ${f.absolutePath}......")
                    shell.exec("cp ${f.absolutePath} ${tmpDir.absolutePath}/${rootName}/conf")
                }
            }
        }


        logger.info("** Generate hbase-env.sh ......")
        def hbaseEnv = new File("${tmpDir.absolutePath}/${rootName}/conf/hbase-env.sh")
        config.hbaseEnv.flatten().each { entry ->
            logger.info "** Add ${entry.key}=${entry.value}"
            hbaseEnv.append("${System.getProperty("line.separator")}export ${entry.key}=${entry.value}")
        }



        logger.info("** Re-generate ${rootName}.tar ......")
        rt = shell.exec("tar -cvzf  ${tmpDir.absolutePath}/${rootName}.tar -C ${tmpDir.absolutePath} ./${rootName}")

        logger.info("** Deploy ${rootName}.tar ......")

        rt = osBuilder.deploy(new File("${tmpDir.absolutePath}/${rootName}.tar"), host, "hbase", "HBASE_HOME");
        tmpDir.deleteDir()
        if (rt != 1) {
            logger.error "Failed to deploy ${deployable} on ${host}"
            return -1
        }


        logger.info "** Create corresponding folders on ${host} ...."

        def ug = shell.sshug(host)
        def group = ug.g
        def user = ug.u
        new File(generate, "folder").eachLine { f ->
            if (f) {
                f = f.replaceAll(",", " ")
                f.split().each { p ->
                    def pathEle = new StringBuffer()
                    p.split(File.separator).each { ele ->
                        if (ele) {
                            pathEle.append(File.separator).append(ele)
                            rt = shell.exec("ls -l ${pathEle.toString()}", host)
                            if (rt.code) {
                                logger.info("**[@${host}]: Creating folder: ${pathEle.toString()} ... ")
                                rt = shell.exec("sudo mkdir ${pathEle.toString()}", host)
                                rt = shell.exec("sudo chown ${user}:${group} ${pathEle.toString()}", host)
                                logger.info("**[@${host}]: Changing owner: ${pathEle.toString()}")
                            }

                        }
                    }
                }
            }
        }
    } else {
        logger.error "${host} is not in the server list: ${config.setting.hosts.toString()}"
    }
}

if (!args) {
    logger.info("make sure your settings are correct and then run the command : init, build, cfg, deploy")
} else {
    if ("init".equalsIgnoreCase(args[0])) {
        new File("hbaseCfg.groovy").withWriter { w ->
            w << new File(currentPath, "hbaseCfg.groovy").text
        }
        logger.info "** Please do the changes according to your environments in hbaseCfg.groovy"
    } else {
        if (!configFile.exists()) {
            logger.error "** hosts is null, please run init first ......"
            return -1
        }
        if ('build'.equalsIgnoreCase(args[0])) {
            buildOs(args.length > 1 ? true : false)
        } else if ("cfg".equalsIgnoreCase(args[0])) {
            cfg()
        } else if ("deploy".equalsIgnoreCase(args[0]) && args.length == 3) {
            def deployable = new File(args[1])
            if (!deployable.exists()) {
                logger.error "Can't find the file ${deployable.absolutePath} ......"
                return -1
            }
            deploy(deployable, args[2])
        } else {
            logger.error("Can not find the command ${args[0]} ...")
        }
    }
}
