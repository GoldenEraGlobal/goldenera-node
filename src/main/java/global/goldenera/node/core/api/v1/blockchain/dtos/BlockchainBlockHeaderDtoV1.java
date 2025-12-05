package global.goldenera.node.core.api.v1.blockchain.dtos;

import static lombok.AccessLevel.PRIVATE;

import global.goldenera.cryptoj.common.BlockHeader;
import global.goldenera.cryptoj.datatypes.Hash;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@FieldDefaults(level = PRIVATE)
public class BlockchainBlockHeaderDtoV1 {

    BlockHeader header;

    BlockchainBlockHeaderMetadataDtoV1 metadata;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @ToString
    @FieldDefaults(level = PRIVATE)
    public static class BlockchainBlockHeaderMetadataDtoV1 {

        Hash hash;
        int size;
        int numOfTxs;

    }
}
