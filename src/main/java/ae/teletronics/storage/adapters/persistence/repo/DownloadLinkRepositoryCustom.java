package ae.teletronics.storage.adapters.persistence.repo;

public interface DownloadLinkRepositoryCustom {
    void incrementAccessCountByToken(String token);
}
