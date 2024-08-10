package reentrancyguard;
import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Msg;
import io.nuls.contract.sdk.annotation.View;

import static io.nuls.contract.sdk.Utils.require;

public class ReentrancyGuard{

    private Boolean status;

    public ReentrancyGuard() {
        this.status = false;
    }

    protected void setEntrance(){

        require(!this.status, "ReentrancyGuard Reverted");

        this.status = true;
    }

    protected void setClosure(){
        this.status = false;
    }

    protected Boolean getReentrancyStatus() {
      return status;
    }
}