// CloudFront Function (viewer request, runtime cloudfront-js-2.0), on the default (web bucket) behaviour
// only. The web app is a single-page app: a path such as /post/abc has no file in the bucket, so it is
// served /index.html and the app's router reads the path. Doing it here, rather than with distribution-wide
// 403/404 error responses, leaves the API's own 403/404 answers under /api/* untouched.
// Plain ES5 on purpose: CloudFront Functions run a restricted JavaScript runtime.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  if (uri.indexOf('/api/') === 0 || uri.indexOf('/media/') === 0) {
    return request;
  }
  var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
