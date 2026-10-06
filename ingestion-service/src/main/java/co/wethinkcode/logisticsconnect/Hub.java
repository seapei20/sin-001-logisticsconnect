package co.wethinkcode.logisticsconnect;

import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Hub hub = (Hub) o;
        return active == hub.active
                && Objects.equals(hubId, hub.hubId)
                && Objects.equals(province, hub.province)
                && Objects.equals(sortingCenter, hub.sortingCenter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hubId, province, sortingCenter, active);
    }

    @Override
    public String toString() {
        return "Hub{" + hubId + ", " + province + ", " + sortingCenter + ", active=" + active + "}";
    }
}
