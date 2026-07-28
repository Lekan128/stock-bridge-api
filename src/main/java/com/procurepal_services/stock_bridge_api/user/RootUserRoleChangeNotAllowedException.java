package com.procurepal_services.stock_bridge_api.user;

/** The account holder's role is fixed for everyone, including themselves - see User.root. */
public class RootUserRoleChangeNotAllowedException extends RuntimeException {

    public RootUserRoleChangeNotAllowedException() {
        super("The account owner's role cannot be changed.");
    }
}
