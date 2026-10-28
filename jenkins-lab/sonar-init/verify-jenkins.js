// Run inside Jenkins: docker compose -p sonar-lab exec -T jenkins node < sonar-init/verify-jenkins.js
const fs = require('node:fs');
async function main() {
    const headers = { Authorization: 'Basic ' + Buffer.from('admin:' +
        fs.readFileSync('/run/secrets/jenkins-admin-password', 'utf8').trim()).toString('base64') };
    const host = 'http://localhost:8080';
    const response = await fetch(host + '/crumbIssuer/api/json', { headers });
    if (!response.ok) throw new Error(`Jenkins administrator authentication: HTTP ${response.status}`);
    headers.Cookie = response.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
    const crumb = await response.json();
    headers[crumb.crumbRequestField] = crumb.crumb;
    if (process.env.JENKINSFILE_TO_VALIDATE) {
        const form = new FormData();
        form.append('jenkinsfile', fs.readFileSync(process.env.JENKINSFILE_TO_VALIDATE, 'utf8'));
        const lint = await fetch(host + '/pipeline-model-converter/validate', { method: 'POST', headers, body: form });
        const result = await lint.text();
        if (!lint.ok || !result.includes('Jenkinsfile successfully validated')) {
            console.error('Jenkinsfile validator HTTP ' + lint.status);
            if (result.startsWith('Errors encountered validating Jenkinsfile:')) console.error(result.slice(0, 5000));
            throw new Error('Jenkinsfile validation failed');
        }
        console.log('Jenkinsfile successfully validated');
        if (process.env.JENKINSFILE_VALIDATE_ONLY === 'true') return;
    }
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    const script = `
import jenkins.model.Jenkins
import com.cloudbees.plugins.credentials.CredentialsProvider
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.plugins.sonar.SonarGlobalConfiguration
import groovy.json.JsonSlurper
def j=Jenkins.instance
boolean failed=false
def props=j.globalNodeProperties.get(EnvironmentVariablesNodeProperty.class)?.envVars ?: [:]
def cs=CredentialsProvider.lookupCredentials(StringCredentials.class,j,null,null)
def installation=j.getDescriptorByType(SonarGlobalConfiguration.class).installations.find{it.name=='SonarQube'}
if (installation?.serverUrl!='http://sonarqube:9000' || installation?.credentialsId!='sonar-product-token') {
    println('FAIL: SonarQube installation configuration'); failed=true
}
def request={ endpoint, token ->
    def con=new URL('http://sonarqube:9000/api/'+endpoint).openConnection()
    con.connectTimeout=15000; con.readTimeout=15000
    con.setRequestProperty('Authorization','Bearer '+token)
    def status=con.responseCode
    def body=[:]
    if (endpoint.startsWith('authentication/') || status==404) {
        def stream=status==200?con.inputStream:con.errorStream
        if(stream) { try { body=new JsonSlurper().parse(stream) } finally {stream.close()} }
    }
    con.disconnect()
    [status,body]
}
['product','user','media','order','gateway','discovery','frontend'].each { c ->
    def credential=cs.find{it.id=='sonar-'+c+'-token'}
    def key=props['SONAR_'+c.toUpperCase()+'_PROJECT_KEY']
    def expected=System.getenv('SONAR_'+c.toUpperCase()+'_TOKEN')
    if(!credential || !key || !expected) {
        println('FAIL '+c+': missing loaded credential, exact project key, or startup token'); failed=true
    } else {
        def token=credential.secret.plainText
        if(token!=expected) {
            println('FAIL '+c+': Jenkins credential differs from startup secret'); failed=true
        } else {
            def auth=request('authentication/validate',token)
            def permission=request('analysis_cache/get?project='+URLEncoder.encode(key,'UTF-8'),token)
            boolean authorized=permission[0]==200 || (permission[0]==404 && permission[1].errors?.any{it.msg=='No cache for given branch or pull request'})
            if(auth[0]==200 && auth[1].valid && authorized) {
                println('PASS '+c+': '+key+'; credential matches startup secret and Execute Analysis verified')
            } else {
                println('FAIL '+c+': authentication HTTP '+auth[0]+'; permission HTTP '+permission[0]); failed=true
            }
        }
    }
}
if(failed) println('SAFE_ZONE_VERIFY_FAILED')
else println('SAFE_ZONE_VERIFY_PASSED')
`;
    const checked = await fetch(host + '/scriptText', { method: 'POST', headers, body: new URLSearchParams({ script }) });
    if (!checked.ok) throw new Error(`Jenkins verification request: HTTP ${checked.status}`);
    const text = await checked.text();
    // Only controlled result lines leave the controller; never dump Groovy exceptions.
    for (const line of text.split(/\r?\n/).filter(line => /^(PASS |FAIL |SAFE_ZONE_VERIFY_)/.test(line))) console.log(line);
    if (!text.includes('SAFE_ZONE_VERIFY_PASSED')) process.exitCode = 1;
}
main().catch(error => {
    console.error(/^(Jenkins administrator authentication: HTTP|Jenkins verification request: HTTP|Jenkinsfile validation failed)/.test(error.message)
        ? error.message : 'Jenkins verification could not complete');
    process.exitCode = 1;
});
