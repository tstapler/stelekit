/**
 * Cloudflare Pages Function — redirect F-Droid APK requests to GitHub Releases.
 *
 * The fdroid index at /fdroid/repo/index-v2.json lists APK filenames (e.g.
 * "SteleKit-v0.47.2-android.apk"). The F-Droid client constructs the download
 * URL as repo_url + "/" + apkName, which lands here. We redirect to the
 * matching GitHub Release asset so APKs are served from GitHub Releases rather
 * than being bundled in the Pages deployment.
 *
 * Incoming:  GET /fdroid/repo/SteleKit-v0.47.2-android.apk
 * Redirects: https://github.com/tstapler/stelekit/releases/download/v0.47.2/SteleKit-v0.47.2-android.apk
 */
export function onRequest(context) {
  const apk = context.params.apk;

  const match = apk.match(/^SteleKit-(v[\d]+\.[\d]+\.[\d]+)-android\.apk$/);
  if (!match) {
    return new Response('Not found', { status: 404 });
  }

  const version = match[1];
  const url = `https://github.com/tstapler/stelekit/releases/download/${version}/${apk}`;
  return Response.redirect(url, 302);
}
