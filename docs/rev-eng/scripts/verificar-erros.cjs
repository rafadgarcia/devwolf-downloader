// Uso: node verificar-erros.cjs <raiz-do-projeto>
// Executa somente o detector real, transpilado em memória; não modifica fontes.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const root = path.resolve(process.argv[2] || '.');
const projectRequire = createRequire(path.join(root, 'frontend/package.json'));
const ts = projectRequire('typescript');
const relative = 'frontend/src/services/hybridOrchestrator.ts';
const source = fs.readFileSync(path.join(root, relative), 'utf8');
const native = fs.readFileSync(path.join(root, 'android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt'), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const moduleObject = { exports: {} };
const sandbox = { module: moduleObject, exports: moduleObject.exports, require(id) {
  // Imports de outras funções do módulo não são utilizados pelo detector.
  if (id === '../utils/format' || id === '../utils/url') return {};
  throw new Error('Import não previsto: ' + id);
}};
vm.runInNewContext(js, sandbox, { filename: relative, timeout: 2000 });
const matches = [...native.matchAll(/"(TERMINAL_[A-Z_]+)" to "([^"]+)"/g)];
if (!matches.length) throw new Error('Payloads nativos não encontrados');
const cases = matches.map(([,code,message]) => ({code,message,terminalRecognized: moduleObject.exports.isTerminalPlatformError(code,message)}));
console.log(JSON.stringify({scope:'Detector real do frontend com mensagens extraídas do Android; sem rede',source:relative,sha256:crypto.createHash('sha256').update(source).digest('hex'),cases},null,2));
