package pt.ulisboa.tecnico.cnv.smartbalancer;

final class BackendResponse {
    final int status;
    final String contentType;
    final byte[] body;

    BackendResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }
}
