package co.wethinkcode.logisticsconnect;

public class Hub {

    private String hubId;
    private String province;
    private String sortingCenter;
    private boolean active;

    public Hub() {}

    public Hub(String hubId, String province, String sortingCenter, boolean active) {
        this.hubId = hubId;
        this.province = province;
        this.sortingCenter = sortingCenter;
        this.active = active;
    }

    public String getHubId() { return hubId; }
    public void setHubId(String hubId) { this.hubId = hubId; }

    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }

    public String getSortingCenter() { return sortingCenter; }
    public void setSortingCenter(String sortingCenter) { this.sortingCenter = sortingCenter; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}