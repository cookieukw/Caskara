package com.cookie.caskara.scanner;

import com.cookie.caskara.annotations.CaskaraEntity;
import com.cookie.caskara.annotations.Id;

@CaskaraEntity(shell = "scanned_dummy")
public class ScannerDummyEntity {
    @Id
    public String myId;
}
