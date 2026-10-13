'use strict';

// ---------------------------------------------------------------------------
// SVN Agent — embedded OpenCode core entry.
//
// This file is the unified, bundled entrypoint that runs inside the embedded
// Node.js engine (nodejs-mobile libnode.so). In a release build it is the
// esbuild/webpack output of the OpenCode core execution loop, produced by
// tools/build_opencode_bundle.sh, which runs `npm prune --production` and
// bundles the source into this single compressed file.
//
// It boots on 127.0.0.1:<SVN_ENGINE_PORT> so the native Android UI can talk
// to the engine over the loopback HTTP bridge (terminal output is streamed
// separately through the process stdout pipe).
// ---------------------------------------------------------------------------

const http = require('http');

const PORT = parseInt(process.env.SVN_ENGINE_PORT || '4096', 10);
const HOST = '127.0.0.1';

function log(line) {
  process.stdout.write(String(line) + '\n');
}

log('SVN // embedded node engine ' + process.version);
log('arch=' + process.arch + ' platform=' + process.platform +
    ' mobile=' + (process.versions.mobile || 'none'));
log('home=' + (process.env.SVN_HOME || process.env.HOME || ''));

// Mount point for the bundled OpenCode core. The real bundle (built by
// tools/build_opencode_bundle.sh) replaces this stub and drops a ./core.js
// whose module exports the execution loop.
let core = null;
try {
  core = require('./core.js');
} catch (e) {
  core = null;
}

if (core && typeof core.boot === 'function') {
  log('opencode core: booting');
  core.boot({
    port: PORT,
    home: process.env.SVN_HOME || process.env.HOME,
    onOutput: log
  });
}

const routes = {
  '/health': function () {
    return { ok: true, node: process.version, mobile: process.versions.mobile || null };
  },
  '/versions': function () {
    return { versions: process.versions, env: { HOME: process.env.HOME || null } };
  },
  '/input': function (body) {
    if (core && typeof core.handleInput === 'function') {
      return core.handleInput(body);
    }
    log('> ' + body);
    return { ok: true, echo: body };
  }
};

const server = http.createServer(function (req, res) {
  let body = '';
  req.on('data', function (chunk) { body += chunk; });
  req.on('end', function () {
    const path = req.url.split('?')[0];
    const handler = routes[path];
    let out;
    try {
      out = handler ? handler(body) : { error: 'not found', path: path };
    } catch (e) {
      out = { error: String(e && e.message ? e.message : e) };
    }
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify(out));
  });
});

server.listen(PORT, HOST, function () {
  log('listening on http://' + HOST + ':' + PORT);
  log('ready.');
});

process.on('uncaughtException', function (e) {
  log('uncaughtException: ' + (e && e.stack ? e.stack : e));
});
